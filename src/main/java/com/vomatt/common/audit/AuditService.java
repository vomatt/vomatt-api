package com.vomatt.common.audit;

import java.time.OffsetDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {
    
    private final AuditLogRepository auditLogRepository;
    private final com.vomatt.repository.UserRepository userRepository;
    @Value("${app.audit.enabled:true}")
    private boolean auditEnabled;
    // @Async runs on unbounded virtual threads; cap concurrent audit writes so they cannot drain the request connection pool
    private final Semaphore writePermits = new Semaphore(2);
    long permitWaitMillis = 2000;
    
    @Async
    public void logAction(String userId, String username, String action, String resourceType, String resourceId) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            save(auditLog);
            
            log.debug("Audit log created: user={}, action={}, resource={}/{}", 
                     username, action, resourceType, resourceId);
        } catch (Exception e) {
            log.error("Failed to create audit log", e);
        }
    }
    
    @Async
    public void logAction(String userId, String username, String action, String resourceType, String resourceId, 
                         String ipAddress, String userAgent, String requestUri, String httpMethod, String details) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setRequestUri(requestUri);
            auditLog.setHttpMethod(httpMethod);
            auditLog.setDetails(details);
            save(auditLog);
            
            log.debug("Detailed audit log created: user={}, action={}, resource={}/{}, from={}", 
                     username, action, resourceType, resourceId, ipAddress);
        } catch (Exception e) {
            log.error("Failed to create detailed audit log", e);
        }
    }
    
    @Async
    public void logFailedAction(String userId, String username, String action, String resourceType, 
                               String resourceId, String errorMessage, String ipAddress, String details) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            auditLog.setSuccess(false);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setIpAddress(ipAddress);
            auditLog.setDetails(details);
            save(auditLog);
            
            log.warn("Failed action logged: user={}, action={}, error={}", username, action, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    /**
     * Resolves the actor on the writer, under the write permit so the lookup shares the audit connection budget. The
     * token carries no username, so it is read here. An actor that no longer exists (account deleted, possibly after
     * this entry was queued) is not re-identified: id, username, IP and user agent are dropped, and when the entry is
     * the deletion of that very account its resource id and request URI (which spell the id) go too.
     */
    private void resolveActor(AuditLog auditLog) {
        String userId = auditLog.getUserId();
        if (userId == null) {
            return;
        }
        java.util.Optional<com.vomatt.entity.User> actor;
        try {
            actor = userRepository.findById(java.util.UUID.fromString(userId));
        } catch (RuntimeException e) {
            actor = java.util.Optional.empty();
        }
        if (actor.isPresent()) {
            if (auditLog.getUsername() == null) {
                auditLog.setUsername(actor.get().getUsername());
            }
            return;
        }
        auditLog.setUserId(null);
        auditLog.setUsername(null);
        auditLog.setIpAddress(null);
        auditLog.setUserAgent(null);
        if ("DELETE".equals(auditLog.getAction()) && "USER".equals(auditLog.getResourceType())
                && userId.equals(auditLog.getResourceId())) {
            auditLog.setResourceId(null);
            auditLog.setRequestUri(null);
        }
    }

    // Runs on the async thread, so waiting here never blocks a request; under sustained overload the entry is dropped
    private void save(AuditLog auditLog) {
        boolean acquired;
        try {
            acquired = writePermits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            log.warn("Audit log dropped (writers busy): action={}, resource={}/{}",
                     auditLog.getAction(), auditLog.getResourceType(), auditLog.getResourceId());
            return;
        }
        try {
            resolveActor(auditLog);
            auditLogRepository.save(auditLog);
        } finally {
            writePermits.release();
        }
    }

    public long getFailedActionsCount() {
        return auditLogRepository.countFailedActionsLast24Hours(OffsetDateTime.now().minusHours(24));
    }
}
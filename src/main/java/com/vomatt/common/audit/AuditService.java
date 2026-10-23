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
            AuditLog auditLog = new AuditLog(userId, usernameOf(userId, username), action, resourceType, resourceId);
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
            AuditLog auditLog = new AuditLog(userId, usernameOf(userId, username), action, resourceType, resourceId);
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
    
    // Runs on the async thread, so waiting here never blocks a request; under sustained overload the entry is dropped
    /**
     * The token carries no email or username, so the actor's username is read here, on the async writer rather than
     * the request thread; a username given by the caller is kept, and an unknown account (deleted) yields null.
     */
    private String usernameOf(String userId, String username) {
        if (username != null || userId == null) {
            return username;
        }
        try {
            return userRepository.findById(java.util.UUID.fromString(userId)).map(com.vomatt.entity.User::getUsername).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

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
            auditLogRepository.save(auditLog);
        } finally {
            writePermits.release();
        }
    }

    public long getFailedActionsCount() {
        return auditLogRepository.countFailedActionsLast24Hours(OffsetDateTime.now().minusHours(24));
    }
}
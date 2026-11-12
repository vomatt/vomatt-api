package com.vomatt.common.audit;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.util.ClientIpResolver;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {
    
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    // @Async runs on unbounded virtual threads; cap concurrent audit writes so they cannot drain the request connection pool
    private final Semaphore writePermits = new Semaphore(2);
    long permitWaitMillis = 2000;
    
    @Async
    public void logAction(String userId, String action, String resourceType, String resourceId, 
                         String ipAddress, String userAgent, String requestUri, String httpMethod, String details) {
        try {
            AuditLog auditLog = new AuditLog(userId, null, action, resourceType, resourceId);
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setRequestUri(requestUri);
            auditLog.setHttpMethod(httpMethod);
            auditLog.setDetails(details);
            save(auditLog);
            
            log.debug("Detailed audit log created: action={}, resource={}", action, resourceType);
        } catch (Exception e) {
            log.error("Failed to create detailed audit log", e);
        }
    }
    
    /**
     * Written now in the caller's transaction instead of after return by {@link #logAction}, which drops entries when
     * its writers are busy: it commits or rolls back with the change it records. In a request whose {@code @Auditable}
     * action is the same, it is that request's success entry and AuditAspect skips its own. A null actor is the system.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void logActionNow(String userId, String action, String resourceType, String resourceId, String details) {
        AuditLog auditLog = new AuditLog(userId, null, action, resourceType, resourceId);
        HttpServletRequest request = AuditAspect.currentRequest();
        if (request != null) {
            auditLog.setIpAddress(ClientIpResolver.resolve(request));
            auditLog.setUserAgent(request.getHeader("User-Agent"));
            auditLog.setRequestUri(request.getRequestURI());
            auditLog.setHttpMethod(request.getMethod());
        }
        auditLog.setDetails(AuditAspect.withActingAs(details));
        resolveActor(auditLog);
        auditLogRepository.save(auditLog);
        AuditAspect.markSuccessEntryWritten(action);
    }

    @Async
    public void logFailedAction(String userId, String action, String resourceType, 
                               String resourceId, String errorMessage, String ipAddress, String details) {
        try {
            AuditLog auditLog = new AuditLog(userId, null, action, resourceType, resourceId);
            auditLog.setSuccess(false);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setIpAddress(ipAddress);
            auditLog.setDetails(details);
            save(auditLog);
            
            log.warn("Failed action logged: action={}, resource={}, error={}", action, resourceType, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    /**
     * Resolves the actor on the writer, under the write permit so the lookup shares the audit connection budget. The
     * token carries no username, so it is read here. An actor that no longer exists (account deleted, possibly after
     * this entry was queued) is not re-identified: id, username, IP and user agent are dropped, and when the entry's
     * resource is that same account (its own deletion) the resource id and request URI, which spell the id, go too.
     * A lookup failure is not "gone": the entry keeps its id and only lacks the username. The daily RetentionJob
     * sweep applies the same rule to entries written while the deletion was still uncommitted.
     */
    private void resolveActor(AuditLog auditLog) {
        String userId = auditLog.getUserId();
        if (userId == null) {
            return;
        }
        Optional<User> actor;
        try {
            actor = userRepository.findById(UUID.fromString(userId));
        } catch (RuntimeException e) {
            log.warn("Audit actor lookup failed, entry kept as given: userId={}, {}", userId, e.getMessage());
            return;
        }
        if (actor.isPresent()) {
            auditLog.setUsername(actor.get().getUsername());
            return;
        }
        auditLog.setUserId(null);
        auditLog.setUsername(null);
        auditLog.setIpAddress(null);
        auditLog.setUserAgent(null);
        if (sameId(userId, auditLog.getResourceId())) {
            auditLog.setResourceId(null);
            auditLog.setRequestUri(null);
        }
    }

    /** UUID equality independent of spelling, so "ABC-..." and "abc-..." compare as the same id. */
    private static boolean sameId(String a, String b) {
        try {
            return b != null && UUID.fromString(a).equals(UUID.fromString(b));
        } catch (IllegalArgumentException e) {
            return false;
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
}
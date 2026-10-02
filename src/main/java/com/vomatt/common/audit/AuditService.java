package com.vomatt.common.audit;

import java.time.OffsetDateTime;

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
    @Value("${app.audit.enabled:true}")
    private boolean auditEnabled;
    
    @Async
    public void logAction(String userId, String username, String action, String resourceType, String resourceId) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            auditLogRepository.save(auditLog);
            
            log.debug("Audit log created: user={}, action={}, resource={}/{}", 
                     username, action, resourceType, resourceId);
        } catch (Exception e) {
            log.error("Failed to create audit log", e);
        }
    }
    
    @Async
    public void logAction(String userId, String username, String action, String resourceType, String resourceId, 
                         String ipAddress, String userAgent, String requestUri, String httpMethod) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setRequestUri(requestUri);
            auditLog.setHttpMethod(httpMethod);
            auditLogRepository.save(auditLog);
            
            log.debug("Detailed audit log created: user={}, action={}, resource={}/{}, from={}", 
                     username, action, resourceType, resourceId, ipAddress);
        } catch (Exception e) {
            log.error("Failed to create detailed audit log", e);
        }
    }
    
    @Async
    public void logFailedAction(String userId, String username, String action, String resourceType, 
                               String resourceId, String errorMessage, String ipAddress) {
        if (!auditEnabled) {
            return;
        }
        
        try {
            AuditLog auditLog = new AuditLog(userId, username, action, resourceType, resourceId);
            auditLog.setSuccess(false);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setIpAddress(ipAddress);
            auditLogRepository.save(auditLog);
            
            log.warn("Failed action logged: user={}, action={}, error={}", username, action, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    public long getFailedActionsCount() {
        return auditLogRepository.countFailedActionsLast24Hours(OffsetDateTime.now().minusHours(24));
    }
}
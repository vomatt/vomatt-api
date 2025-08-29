package com.vomattapi.infrastructure.audit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.vomattapi.application.security.services.UserDetailsImpl;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditAspect {
    
    private final AuditService auditService;
    
    @AfterReturning("@annotation(auditable)")
    public void logSuccessfulAction(JoinPoint joinPoint, Auditable auditable) {
        try {
            String[] userInfo = getCurrentUserInfo();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null && userInfo[0] != null) {
                String resourceId = extractResourceId(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logAction(
                    userInfo[0], // userId
                    userInfo[1], // username
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    getClientIpAddress(request),
                    request.getHeader("User-Agent"),
                    request.getRequestURI(),
                    request.getMethod()
                );
            }
        } catch (Exception e) {
            log.error("Failed to log successful action", e);
        }
    }
    
    @AfterThrowing(pointcut = "@annotation(auditable)", throwing = "exception")
    public void logFailedAction(JoinPoint joinPoint, Auditable auditable, Throwable exception) {
        try {
            String[] userInfo = getCurrentUserInfo();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null) {
                String resourceId = extractResourceId(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logFailedAction(
                    userInfo[0], // userId (might be null for unauthenticated requests)
                    userInfo[1], // username (might be null)
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    exception.getMessage(),
                    getClientIpAddress(request)
                );
            }
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    private String[] getCurrentUserInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl) {
            UserDetailsImpl memberDetails = (UserDetailsImpl) authentication.getPrincipal();
            return new String[]{memberDetails.getId(), memberDetails.getUsername()};
        }
        return new String[]{null, null};
    }
    
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes requestAttributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return requestAttributes != null ? requestAttributes.getRequest() : null;
    }
    
    private String extractResourceId(Object[] args, int index) {
        if (index >= 0 && index < args.length && args[index] != null) {
            return args[index].toString();
        }
        return null;
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}
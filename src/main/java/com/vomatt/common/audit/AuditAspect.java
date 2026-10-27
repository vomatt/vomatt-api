package com.vomatt.common.audit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.common.util.ClientIpResolver;

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
            String userId = currentUserId();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null && userId != null) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logAction(
                    userId,
                    null, // username: resolved by AuditService
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    ClientIpResolver.resolve(request),
                    request.getHeader("User-Agent"),
                    request.getRequestURI(),
                    request.getMethod(),
                    details(joinPoint.getArgs(), auditable.detailsIndex())
                );
            }
        } catch (Exception e) {
            log.error("Failed to log successful action", e);
        }
    }
    
    @AfterThrowing(pointcut = "@annotation(auditable)", throwing = "exception")
    public void logFailedAction(JoinPoint joinPoint, Auditable auditable, Throwable exception) {
        // Rate-limited calls are not audited, so a spammer cannot turn rejections into DB writes;
        // other 4xx (401, 403, ...) stay audited as signs of probing
        if (exception instanceof ApiException api && api.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            return;
        }
        try {
            String userId = currentUserId();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logFailedAction(
                    userId, // null for unauthenticated requests
                    null, // username: resolved by AuditService
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    exception.getMessage(),
                    ClientIpResolver.resolve(request),
                    details(joinPoint.getArgs(), auditable.detailsIndex())
                );
            }
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    /** The caller's user id, or null; the token carries no username, AuditService resolves the actor on its async writer. */
    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
    
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes requestAttributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return requestAttributes != null ? requestAttributes.getRequest() : null;
    }
    
    private String argString(Object[] args, int index) {
        if (index >= 0 && index < args.length && args[index] != null) {
            return args[index].toString();
        }
        return null;
    }
    
    private String details(Object[] args, int index) {
        if (index >= 0 && index < args.length && args[index] instanceof AuditDetails details) {
            return details.auditDetails();
        }
        return argString(args, index);
    }

}

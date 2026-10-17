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
            String[] userInfo = getCurrentUserInfo();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null && userInfo[0] != null) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logAction(
                    userInfo[0], // userId
                    userInfo[1], // username
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    ClientIpResolver.resolve(request),
                    request.getHeader("User-Agent"),
                    request.getRequestURI(),
                    request.getMethod(),
                    argString(joinPoint.getArgs(), auditable.detailsIndex())
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
            String[] userInfo = getCurrentUserInfo();
            HttpServletRequest request = getCurrentRequest();
            
            if (request != null) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logFailedAction(
                    userInfo[0], // userId (might be null for unauthenticated requests)
                    userInfo[1], // username (might be null)
                    auditable.action(),
                    auditable.resourceType(),
                    resourceId,
                    exception.getMessage(),
                    ClientIpResolver.resolve(request),
                    argString(joinPoint.getArgs(), auditable.detailsIndex())
                );
            }
        } catch (Exception e) {
            log.error("Failed to log failed action", e);
        }
    }
    
    private String[] getCurrentUserInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return new String[]{principal.userId(), principal.email()};
        }
        return new String[]{null, null};
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
    
}
package com.vomatt.common.audit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.security.ActingMember;
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
    
    private static final String RECORDED_DETAILS = AuditAspect.class.getName() + ".details";
    private static final String SUCCESS_ENTRY_WRITTEN = AuditAspect.class.getName() + ".successEntryWritten";

    private final AuditService auditService;

    /**
     * Details only the audited call itself knows, such as the values before a change; they replace the details taken
     * from the arguments. A no-op outside a request.
     */
    public static void recordDetails(String details) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(RECORDED_DETAILS, details, RequestAttributes.SCOPE_REQUEST);
        }
    }
    
    @AfterReturning("@annotation(auditable)")
    public void logSuccessfulAction(JoinPoint joinPoint, Auditable auditable) {
        try {
            String userId = currentUserId();
            HttpServletRequest request = currentRequest();
            
            if (request != null && userId != null && !auditable.action().equals(request.getAttribute(SUCCESS_ENTRY_WRITTEN))) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logAction(
                    userId,
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
            HttpServletRequest request = currentRequest();
            
            if (request != null) {
                String resourceId = argString(joinPoint.getArgs(), auditable.resourceIdIndex());
                
                auditService.logFailedAction(
                    userId, // null for unauthenticated requests
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
    
    /**
     * The caller's user id, or null; the token carries no username, AuditService resolves the actor on its async writer.
     * While acting as a community account the actor is the real member (the community account goes in the details).
     */
    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return ActingMember.realUserId(principal);
        }
        return null;
    }
    
    static void markSuccessEntryWritten(String action) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(SUCCESS_ENTRY_WRITTEN, action);
        }
    }

    static HttpServletRequest currentRequest() {
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
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object recorded = attributes == null ? null : attributes.getAttribute(RECORDED_DETAILS, RequestAttributes.SCOPE_REQUEST);
        return withActingAs(recorded != null ? recorded.toString()
                : index >= 0 && index < args.length && args[index] instanceof AuditDetails auditDetails
                ? auditDetails.auditDetails() : argString(args, index));
    }

    static String withActingAs(String details) {
        return ActingMember.current()
                .map(acting -> "actingAs=" + acting.communityId() + (details == null ? "" : "; " + details))
                .orElse(details);
    }

}

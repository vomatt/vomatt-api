package com.vomatt.common.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

class AuditAspectTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditAspect aspect = new AuditAspect(auditService);
    private final JoinPoint joinPoint = mock(JoinPoint.class);
    private Auditable auditable;

    @Auditable(action = "CAST_BALLOT", resourceType = "VOTE")
    void annotated() {
    }

    @Auditable(action = "SUSPEND", resourceType = "USER", detailsIndex = 0)
    void withDetails() {
    }

    @BeforeEach
    void setUp() throws Exception {
        auditable = getClass().getDeclaredMethod("annotated").getAnnotation(Auditable.class);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }


    @Test
    void shouldPassActorAndResourceThroughControllerWhenDeletingAccounts() {
        com.vomatt.users.UserService userService = mock(com.vomatt.users.UserService.class);
        com.vomatt.users.UserController proxied = proxied(new com.vomatt.users.UserController(userService, mock(com.vomatt.votes.VoteService.class),
                mock(com.vomatt.auth.PasskeyService.class), mock(com.vomatt.common.security.RefreshTokenService.class)));
        java.util.UUID self = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        signIn(self, "alice");
        try {
            proxied.deleteUser(self.toString(), principal(self));
            proxied.deleteUser(other.toString(), principal(self));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        // The aspect records the token's actor as is; AuditService decides on its writer whether the actor still exists
        // (a self-deletion is anonymized there, see AuditServiceTest)
        verify(auditService).logAction(org.mockito.ArgumentMatchers.eq(self.toString()), org.mockito.ArgumentMatchers.eq("DELETE"),
                org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(self.toString()), any(), any(), any(), any(), any());
        verify(auditService).logAction(org.mockito.ArgumentMatchers.eq(self.toString()), org.mockito.ArgumentMatchers.eq("DELETE"),
                org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(other.toString()), any(), any(), any(), any(), any());
    }

    private <T> T proxied(T target) {
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static com.vomatt.common.security.UserPrincipal principal(java.util.UUID id) {
        return new com.vomatt.common.security.UserPrincipal(id.toString(), java.util.List.of("user"), null);
    }

    private void signIn(java.util.UUID id, String username) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal(id), null, java.util.List.of()));
    }

    @Test
    void shouldSkipAuditWhenRateLimited() {
        aspect.logFailedAction(joinPoint, auditable,
                new ApiException(HttpStatus.TOO_MANY_REQUESTS, MessageKey.COMMON_RATE_LIMITED));

        verify(auditService, never()).logFailedAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAuditWhenAccessIsDenied() {
        aspect.logFailedAction(joinPoint, auditable, new ApiException(HttpStatus.FORBIDDEN, MessageKey.COMMON_FORBIDDEN));
        aspect.logFailedAction(joinPoint, auditable, new ApiException(HttpStatus.UNAUTHORIZED, MessageKey.COMMON_UNAUTHORIZED));

        verify(auditService, org.mockito.Mockito.times(2))
                .logFailedAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAuditWhenFailureIsServerError() {
        aspect.logFailedAction(joinPoint, auditable, new IllegalStateException("boom"));
        aspect.logFailedAction(joinPoint, auditable,
                new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageKey.COMMON_INTERNAL_ERROR));

        verify(auditService, org.mockito.Mockito.times(2))
                .logFailedAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldStorePlainReasonAndSortedRolesWhenRequestDefinesDetails() throws Exception {
        Auditable withDetails = getClass().getDeclaredMethod("withDetails").getAnnotation(Auditable.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { new com.vomatt.admin.dto.SuspendUserRequest("spam") });
        aspect.logFailedAction(joinPoint, withDetails, new IllegalStateException("boom"));
        when(joinPoint.getArgs()).thenReturn(new Object[] { new com.vomatt.admin.dto.UpdateRolesRequest(java.util.List.of("user", "admin", "user")) });
        aspect.logFailedAction(joinPoint, withDetails, new IllegalStateException("boom"));

        verify(auditService).logFailedAction(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("spam"));
        verify(auditService).logFailedAction(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("admin,user"));
    }
}

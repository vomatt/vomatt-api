package com.vomatt.admin;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/**
 * standaloneSetup with the controller wrapped in the {@code @PreAuthorize} interceptor (so the class-level ADMIN rule
 * applies without a Spring context) and the {@code @AuthenticationPrincipal} resolver.
 */
final class AdminControllerTestSupport {

    static final String ADMIN_ID = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d00";

    private AdminControllerTestSupport() {}

    static MockMvc mockMvc(Object controller, LocalizedMessageService messageService) {
        ProxyFactory proxyFactory = new ProxyFactory(controller);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return MockMvcBuilders.standaloneSetup(proxyFactory.getProxy())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    /** JwtAuthFilter maps the roles claim to ROLE_<UPPER> authorities */
    static void authenticateAs(String role) {
        UserPrincipal principal = new UserPrincipal(ADMIN_ID, List.of(role), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                AuthorityUtils.createAuthorityList("ROLE_" + role.toUpperCase())));
    }
}

package com.vomattapi.controller;

import com.vomattapi.application.controller.AuthController;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl;
import com.vomattapi.application.service.auth.AuthService;
import com.vomattapi.application.service.auth.AuthSessionService;
import com.vomattapi.application.service.auth.JwtBlacklistService;
import com.vomattapi.application.service.auth.PreSignupService;
import com.vomattapi.application.service.auth.RefreshTokenService;
import com.vomattapi.application.service.auth.SignupService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verify security rules for AuthController endpoints:
 * - Public endpoints (signin, signup, etc.) do not require Token → Security layer passes, handled by business logic
 * - Authenticated endpoints (signout, force-expire-token) without login → Security layer intercepts and returns 401
 *
 * How to distinguish between security layer 401 and business logic 401:
 * - Security layer 401 returns {"status":401,"error":"Unauthorized"}
 * - Business logic returns {"success":false,"errorCode":"..."} → indicates security layer passed
 */
@WebMvcTest(AuthController.class)
@Import(AuthEndpointSecurityTest.SecurityTestConfig.class)
@ActiveProfiles("test")
@DisplayName("AuthController endpoint security rules")
class AuthEndpointSecurityTest {

    /** Fixed error message returned when security layer intercepts */
    private static final String SECURITY_BLOCKED_ERROR = "Unauthorized";

    /**
     * Security rules synchronized with WebSecurityConfig (JWT Filter not included).
     * If WebSecurityConfig auth routes change, this must be updated accordingly.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    (request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write(
                            "{\"status\":401,\"error\":\"Unauthorized\"}");
                    }
                ))
                .authorizeHttpRequests(auth -> auth
                    // Synchronized with WebSecurityConfig: public routes
                    .requestMatchers(
                        "/api/v1/auth/signin",
                        "/api/v1/auth/pre-signup",
                        "/api/v1/auth/signup",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/refreshToken",
                        "/api/v1/auth/generateVerificationCode"
                    ).permitAll()
                    // Synchronized with WebSecurityConfig: authenticated routes
                    .requestMatchers(
                        "/api/v1/auth/signout",
                        "/api/v1/auth/force-expire-token"
                    ).authenticated()
                    .anyRequest().authenticated()
                );
            return http.build();
        }
    }

    @Autowired MockMvc mockMvc;

    @MockBean AuthService authService;
    @MockBean AuthSessionService authSessionService;
    @MockBean RefreshTokenService refreshTokenService;
    @MockBean PreSignupService preSignupService;
    @MockBean SignupService signupService;
    @MockBean JwtUtils jwtUtils;
    @MockBean JwtBlacklistService jwtBlacklistService;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // ─── Authenticated endpoints: unauthenticated requests should be intercepted by security layer ──────────────────────────────────

    @Nested
    @DisplayName("Authenticated endpoints - without Token")
    class AuthenticatedEndpointsWithoutToken {

        @Test
        @DisplayName("POST /signout unauthenticated should be intercepted by security layer and return 401")
        void shouldReturn401WhenSignoutWithoutToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/signout").with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(SECURITY_BLOCKED_ERROR));
        }

        @Test
        @DisplayName("POST /force-expire-token unauthenticated should be intercepted by security layer and return 401")
        void shouldReturn401WhenForceExpireWithoutToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/force-expire-token").with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(SECURITY_BLOCKED_ERROR));
        }
    }

    // ─── Authenticated endpoints: authenticated users should be able to access ──────────────────────────────────────

    @Nested
    @DisplayName("Authenticated endpoints - with valid authentication")
    class AuthenticatedEndpointsWithToken {

        @Test
        @WithMockUser
        @DisplayName("POST /signout authenticated user should pass security layer")
        void shouldPassSecurityWhenSignoutWithToken() throws Exception {
            // After security layer passes, business logic may fail due to mock, but not with security layer's "Unauthorized"
            mockMvc.perform(post("/api/v1/auth/signout").with(csrf()))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @WithMockUser
        @DisplayName("POST /force-expire-token authenticated user should pass security layer")
        void shouldPassSecurityWhenForceExpireWithToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/force-expire-token").with(csrf()))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }
    }

    // ─── Public endpoints: security layer should allow access ──────────────────────────────────────────

    @Nested
    @DisplayName("Public endpoints - no Token required")
    class PublicEndpoints {

        @Test
        @DisplayName("POST /signin security layer should allow access (business logic may return 401 but not security intercept)")
        void shouldPassSecurityForSignin() throws Exception {
            // Security layer allows access, business logic returns 401 due to invalid code, but body has errorCode not "error":"Unauthorized"
            mockMvc.perform(post("/api/v1/auth/signin")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"verificationCode\":\"123456\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /signup security layer should allow access")
        void shouldPassSecurityForSignup() throws Exception {
            mockMvc.perform(post("/api/v1/auth/signup")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"u\",\"email\":\"a@b.com\",\"verificationCode\":\"123456\",\"firstName\":\"F\",\"lastName\":\"L\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /refreshToken security layer should allow access")
        void shouldPassSecurityForRefreshToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refreshToken")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"some-token\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /generateVerificationCode security layer should allow access")
        void shouldPassSecurityForGenerateVerificationCode() throws Exception {
            mockMvc.perform(post("/api/v1/auth/generateVerificationCode")
                            .with(csrf())
                            .param("email", "a@b.com"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }
    }
}

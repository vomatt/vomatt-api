package com.vomattapi.controller;

import com.vomattapi.application.controller.AuthController;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl;
import com.vomattapi.application.service.auth.AuthService;
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
 * 驗證 AuthController 端點的安全規則：
 * - 公開端點（signin, signup 等）不需要 Token → 安全層放行，由業務邏輯處理
 * - 需認證端點（signout, force-expire-token）未登入時 → 安全層攔截回傳 401
 *
 * 區分安全層 401 與業務邏輯 401 的方式：
 * - 安全層 401 回傳 {"status":401,"error":"Unauthorized"}
 * - 業務邏輯回傳 {"success":false,"errorCode":"..."} → 表示安全層已通過
 */
@WebMvcTest(AuthController.class)
@Import(AuthEndpointSecurityTest.SecurityTestConfig.class)
@ActiveProfiles("test")
@DisplayName("AuthController 端點安全規則")
class AuthEndpointSecurityTest {

    /** 安全層攔截時回傳的固定錯誤訊息 */
    private static final String SECURITY_BLOCKED_ERROR = "Unauthorized";

    /**
     * 與 WebSecurityConfig 同步的安全規則（不含 JWT Filter）。
     * 若 WebSecurityConfig 的 auth 路由變更，此處也需同步更新。
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
                    // 與 WebSecurityConfig 同步：公開路由
                    .requestMatchers(
                        "/api/v1/auth/signin",
                        "/api/v1/auth/pre-signup",
                        "/api/v1/auth/signup",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/refreshToken",
                        "/api/v1/auth/generateVerificationCode"
                    ).permitAll()
                    // 與 WebSecurityConfig 同步：需認證路由
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
    @MockBean RefreshTokenService refreshTokenService;
    @MockBean PreSignupService preSignupService;
    @MockBean SignupService signupService;
    @MockBean JwtUtils jwtUtils;
    @MockBean JwtBlacklistService jwtBlacklistService;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // ─── 需認證端點：未登入應被安全層攔截 ──────────────────────────────────

    @Nested
    @DisplayName("需認證端點 - 未帶 Token")
    class AuthenticatedEndpointsWithoutToken {

        @Test
        @DisplayName("POST /signout 未登入應被安全層攔截回傳 401")
        void shouldReturn401WhenSignoutWithoutToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/signout").with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(SECURITY_BLOCKED_ERROR));
        }

        @Test
        @DisplayName("POST /force-expire-token 未登入應被安全層攔截回傳 401")
        void shouldReturn401WhenForceExpireWithoutToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/force-expire-token").with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(SECURITY_BLOCKED_ERROR));
        }
    }

    // ─── 需認證端點：已登入應可存取 ──────────────────────────────────────

    @Nested
    @DisplayName("需認證端點 - 已登入")
    class AuthenticatedEndpointsWithToken {

        @Test
        @WithMockUser
        @DisplayName("POST /signout 已登入安全層應放行")
        void shouldPassSecurityWhenSignoutWithToken() throws Exception {
            // 安全層放行後，業務邏輯可能因 mock 回傳錯誤，但不會是安全層的 "Unauthorized"
            mockMvc.perform(post("/api/v1/auth/signout").with(csrf()))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @WithMockUser
        @DisplayName("POST /force-expire-token 已登入安全層應放行")
        void shouldPassSecurityWhenForceExpireWithToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/force-expire-token").with(csrf()))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }
    }

    // ─── 公開端點：安全層應放行 ──────────────────────────────────────────

    @Nested
    @DisplayName("公開端點 - 不需要 Token")
    class PublicEndpoints {

        @Test
        @DisplayName("POST /signin 安全層應放行（業務邏輯可能回傳 401 但非安全攔截）")
        void shouldPassSecurityForSignin() throws Exception {
            // 安全層放行，業務邏輯因驗證碼無效回傳 401，但 body 含 errorCode 而非 "error":"Unauthorized"
            mockMvc.perform(post("/api/v1/auth/signin")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"verificationCode\":\"123456\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /signup 安全層應放行")
        void shouldPassSecurityForSignup() throws Exception {
            mockMvc.perform(post("/api/v1/auth/signup")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"u\",\"email\":\"a@b.com\",\"verificationCode\":\"123456\",\"firstName\":\"F\",\"lastName\":\"L\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /refreshToken 安全層應放行")
        void shouldPassSecurityForRefreshToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refreshToken")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"some-token\"}"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }

        @Test
        @DisplayName("POST /generateVerificationCode 安全層應放行")
        void shouldPassSecurityForGenerateVerificationCode() throws Exception {
            mockMvc.perform(post("/api/v1/auth/generateVerificationCode")
                            .with(csrf())
                            .param("email", "a@b.com"))
                    .andExpect(jsonPath("$.error").doesNotExist());
        }
    }
}

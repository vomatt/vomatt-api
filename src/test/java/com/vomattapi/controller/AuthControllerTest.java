package com.vomattapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vomattapi.application.controller.AuthController;
import com.vomattapi.application.dto.auth.SigninRequest;
import com.vomattapi.application.dto.auth.SignupRequest;
import com.vomattapi.application.dto.auth.JwtResponse;
import com.vomattapi.application.exception.InvalidVerificationCodeException;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.application.service.auth.AuthService;
import com.vomattapi.application.service.auth.AuthSessionService;
import com.vomattapi.application.service.auth.JwtBlacklistService;
import com.vomattapi.application.service.auth.PreSignupService;
import com.vomattapi.application.service.auth.RefreshTokenService;
import com.vomattapi.application.service.auth.SignupService;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import(AuthControllerTest.TestSecurityConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

    /** 測試用簡化安全設定：CSRF 停用，所有路徑開放 */
    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthService authService;
    @MockBean AuthSessionService authSessionService;
    @MockBean RefreshTokenService refreshTokenService;
    @MockBean PreSignupService preSignupService;
    @MockBean SignupService signupService;
    @MockBean JwtUtils jwtUtils;
    @MockBean JwtBlacklistService jwtBlacklistService;
    // AuthTokenFilter 是 @Component，需要 mock 其依賴才能建立 Spring context
    @MockBean com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl userDetailsService;

    // ─── POST /api/v1/auth/signin ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/signin")
    class SigninTests {

        @Test
        @DisplayName("驗證碼無效時應回傳 401")
        void shouldReturn401WhenCodeInvalid() throws Exception {
            doThrow(new InvalidVerificationCodeException("Invalid code"))
                    .when(authService).verifyCode("user@test.com", "wrong");

            SigninRequest request = new SigninRequest("user@test.com", "wrong");
            mockMvc.perform(post("/api/v1/auth/signin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("驗證碼正確且用戶存在時應回傳 200 和 JWT")
        void shouldReturn200WithJwtWhenSuccess() throws Exception {
            doNothing().when(authService).verifyCode("user@test.com", "123456");

            JwtResponse jwtResponse = new JwtResponse(
                    "jwt-token", "refresh-token", "user-id", "testuser", "user@test.com",
                    List.of("ROLE_USER"));
            when(authSessionService.createAuthenticatedSession("user@test.com")).thenReturn(jwtResponse);

            SigninRequest request = new SigninRequest("user@test.com", "123456");
            mockMvc.perform(post("/api/v1/auth/signin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").value("jwt-token"));
        }

        @Test
        @DisplayName("請求格式無效時應回傳 400")
        void shouldReturn400OnInvalidRequestBody() throws Exception {
            mockMvc.perform(post("/api/v1/auth/signin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── POST /api/v1/auth/signup ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/signup")
    class SignupTests {

        @Test
        @DisplayName("驗證碼過期時應回傳 400")
        void shouldReturn400WhenVerificationExpired() throws Exception {
            when(signupService.processSignup(any()))
                    .thenReturn(SignupService.SignupResult.failure(
                            com.vomattapi.application.dto.common.ErrorType.VERIFICATION_CODE_EXPIRED,
                            "Verification expired"));

            SignupRequest request = buildValidSignupRequest();
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("成功註冊時應回傳 200 和 JWT")
        void shouldReturn200WithJwtOnSuccess() throws Exception {
            when(signupService.processSignup(any()))
                    .thenReturn(SignupService.SignupResult.success());

            JwtResponse jwtResponse = new JwtResponse(
                    "new-jwt", "new-refresh", "user-id", "newuser", "new@test.com",
                    List.of("ROLE_USER"));
            when(authSessionService.createAuthenticatedSession("new@test.com")).thenReturn(jwtResponse);

            SignupRequest request = buildValidSignupRequest();
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.token").value("new-jwt"));
        }

        private SignupRequest buildValidSignupRequest() {
            SignupRequest req = new SignupRequest();
            req.setUsername("newuser");
            req.setEmail("new@test.com");
            req.setVerificationCode("123456");
            req.setFirstName("New");
            req.setLastName("User");
            return req;
        }
    }
}

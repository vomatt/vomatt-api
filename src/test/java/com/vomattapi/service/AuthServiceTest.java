package com.vomattapi.service;

import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.InvalidVerificationCodeException;
import com.vomattapi.application.service.auth.AuthService;
import com.vomattapi.application.service.shared.EmailService;
import com.vomattapi.application.service.user.UserService;
import com.vomattapi.application.service.auth.VerificationCodeService;
import com.vomattapi.domain.user.User;
import com.vomattapi.infrastructure.constants.CacheConstants;
import com.vomattapi.infrastructure.redis.RedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock UserService userService;
    @Mock VerificationCodeService verificationCodeService;
    @Mock RedisService redisService;
    @Mock EmailService emailService;

    @InjectMocks
    AuthService authService;

    // ─── generateVerificationCode ─────────────────────────────────────────────

    @Nested
    @DisplayName("generateVerificationCode")
    class GenerateVerificationCodeTests {

        @Test
        @DisplayName("應該在用戶不存在時拋出 EntityNotFoundException")
        void shouldThrowWhenUserNotFound() {
            when(userService.getUserByEmail("unknown@test.com")).thenReturn(null);

            assertThatThrownBy(() -> authService.generateVerificationCode("unknown@test.com"))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("應該在成功時回傳驗證碼並發送郵件")
        void shouldReturnCodeAndSendEmailOnSuccess() {
            User user = new User();
            when(userService.getUserByEmail("user@test.com")).thenReturn(user);
            when(verificationCodeService.generateVerificationCode()).thenReturn("123456");
            when(userService.changeVerificationCode("user@test.com", "123456")).thenReturn(true);

            String result = authService.generateVerificationCode("user@test.com");

            assertThat(result).isEqualTo("123456");
            verify(redisService).set(eq(CacheConstants.VERIFICATION_CODE), eq("user@test.com"), eq("123456"), any());
            verify(emailService).sendVerificationEmail("user@test.com", "123456");
        }

        @Test
        @DisplayName("應該在 changeVerificationCode 失敗時拋出 BusinessRuleViolationException")
        void shouldThrowWhenUpdateFails() {
            User user = new User();
            when(userService.getUserByEmail("user@test.com")).thenReturn(user);
            when(verificationCodeService.generateVerificationCode()).thenReturn("123456");
            when(userService.changeVerificationCode("user@test.com", "123456")).thenReturn(false);

            assertThatThrownBy(() -> authService.generateVerificationCode("user@test.com"))
                    .isInstanceOf(BusinessRuleViolationException.class);
            verify(emailService, never()).sendVerificationEmail(any(), any());
        }
    }

    // ─── verifyCode ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyCode")
    class VerifyCodeTests {

        @Test
        @DisplayName("應該在驗證碼正確時成功並刪除 Redis 中的紀錄")
        void shouldSucceedAndDeleteOnValidCode() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn("123456");

            assertThatCode(() -> authService.verifyCode("user@test.com", "123456"))
                    .doesNotThrowAnyException();
            verify(redisService).delete(CacheConstants.VERIFICATION_CODE, "user@test.com");
        }

        @Test
        @DisplayName("應該在驗證碼錯誤時拋出 InvalidVerificationCodeException")
        void shouldThrowOnInvalidCode() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn("123456");

            assertThatThrownBy(() -> authService.verifyCode("user@test.com", "999999"))
                    .isInstanceOf(InvalidVerificationCodeException.class);
            verify(redisService, never()).delete(any(), any());
        }

        @Test
        @DisplayName("應該在 Redis 中無驗證碼時拋出 InvalidVerificationCodeException")
        void shouldThrowWhenNoCodeInRedis() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn(null);

            assertThatThrownBy(() -> authService.verifyCode("user@test.com", "123456"))
                    .isInstanceOf(InvalidVerificationCodeException.class);
        }

        @Test
        @DisplayName("應該在 Redis 發生例外時直接拋出（不吞掉）")
        void shouldPropagateWhenRedisThrows() {
            when(redisService.get(any(), any(), any())).thenThrow(new RuntimeException("Redis down"));

            assertThatThrownBy(() -> authService.verifyCode("user@test.com", "123456"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Redis down");
        }
    }
}

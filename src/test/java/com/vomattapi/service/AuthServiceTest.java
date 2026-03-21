package com.vomattapi.service;

import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.service.AuthService;
import com.vomattapi.application.service.EmailService;
import com.vomattapi.application.service.UserService;
import com.vomattapi.application.service.VerificationCodeService;
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
        @DisplayName("應該在 changeVerificationCode 失敗時回傳 null")
        void shouldReturnNullWhenUpdateFails() {
            User user = new User();
            when(userService.getUserByEmail("user@test.com")).thenReturn(user);
            when(verificationCodeService.generateVerificationCode()).thenReturn("123456");
            when(userService.changeVerificationCode("user@test.com", "123456")).thenReturn(false);

            String result = authService.generateVerificationCode("user@test.com");

            assertThat(result).isNull();
            verify(emailService, never()).sendVerificationEmail(any(), any());
        }
    }

    // ─── verificationCode ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("verificationCode")
    class VerifyCodeTests {

        @Test
        @DisplayName("應該在驗證碼正確時回傳 true 並刪除 Redis 中的紀錄")
        void shouldReturnTrueAndDeleteOnValidCode() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn("123456");

            boolean result = authService.verificationCode("user@test.com", "123456");

            assertThat(result).isTrue();
            verify(redisService).delete(CacheConstants.VERIFICATION_CODE, "user@test.com");
        }

        @Test
        @DisplayName("應該在驗證碼錯誤時回傳 false")
        void shouldReturnFalseOnInvalidCode() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn("123456");

            boolean result = authService.verificationCode("user@test.com", "999999");

            assertThat(result).isFalse();
            verify(redisService, never()).delete(any(), any());
        }

        @Test
        @DisplayName("應該在 Redis 中無驗證碼時回傳 false")
        void shouldReturnFalseWhenNoCodeInRedis() {
            when(redisService.get(CacheConstants.VERIFICATION_CODE, "user@test.com", String.class))
                    .thenReturn(null);

            boolean result = authService.verificationCode("user@test.com", "123456");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("應該在 Redis 發生例外時回傳 false（不拋出）")
        void shouldReturnFalseWhenRedisThrows() {
            when(redisService.get(any(), any(), any())).thenThrow(new RuntimeException("Redis down"));

            boolean result = authService.verificationCode("user@test.com", "123456");

            assertThat(result).isFalse();
        }
    }
}

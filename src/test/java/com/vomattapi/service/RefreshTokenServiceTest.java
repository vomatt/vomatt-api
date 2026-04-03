package com.vomattapi.service;

import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.application.service.auth.RefreshTokenService;
import com.vomattapi.domain.user.RefreshToken;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.RefreshTokenRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserRepository userRepository;

    @InjectMocks
    RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenDurationMs", 604800000L); // 7 天
    }

    // ─── createRefreshToken ───────────────────────────────────────────────────

    @Nested
    @DisplayName("createRefreshToken")
    class CreateRefreshTokenTests {

        @Test
        @DisplayName("應該在用戶不存在時拋出 EntityNotFoundException")
        void shouldThrowWhenUserNotFound() {
            String userId = UUID.randomUUID().toString();
            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.createRefreshToken(userId))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(refreshTokenRepository, never()).deleteByUser(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("應該在建立新 token 前刪除舊 token（支援重複 signin）")
        void shouldDeleteExistingTokenBeforeCreatingNew() {
            String userId = UUID.randomUUID().toString();
            User user = new User();
            RefreshToken savedToken = new RefreshToken(user, "new-token", LocalDateTime.now().plusDays(7));

            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
            when(refreshTokenRepository.deleteByUser(user)).thenReturn(1);
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(savedToken);

            RefreshToken result = refreshTokenService.createRefreshToken(userId);

            // 驗證刪除在儲存之前呼叫
            var inOrder = inOrder(refreshTokenRepository);
            inOrder.verify(refreshTokenRepository).deleteByUser(user);
            inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));

            assertThat(result).isNotNull();
            assertThat(result.getToken()).isEqualTo("new-token");
        }

        @Test
        @DisplayName("應該在第一次 signin（無舊 token）時正常建立 token")
        void shouldCreateTokenWhenNoExistingToken() {
            String userId = UUID.randomUUID().toString();
            User user = new User();
            RefreshToken savedToken = new RefreshToken(user, "first-token", LocalDateTime.now().plusDays(7));

            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
            when(refreshTokenRepository.deleteByUser(user)).thenReturn(0); // 沒有舊 token
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(savedToken);

            RefreshToken result = refreshTokenService.createRefreshToken(userId);

            verify(refreshTokenRepository).deleteByUser(user);
            verify(refreshTokenRepository).save(any(RefreshToken.class));
            assertThat(result.getToken()).isEqualTo("first-token");
        }

        @Test
        @DisplayName("應該設定正確的過期時間（refreshExpiration ms 後）")
        void shouldSetCorrectExpiryDate() {
            String userId = UUID.randomUUID().toString();
            User user = new User();
            LocalDateTime before = LocalDateTime.now().plusSeconds(604800 - 1);
            LocalDateTime after = LocalDateTime.now().plusSeconds(604800 + 1);

            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
            when(refreshTokenRepository.deleteByUser(user)).thenReturn(0);
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

            RefreshToken result = refreshTokenService.createRefreshToken(userId);

            assertThat(result.getExpiryDate()).isAfter(before).isBefore(after);
        }
    }

    // ─── verifyExpiration ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyExpiration")
    class VerifyExpirationTests {

        @Test
        @DisplayName("應該在 token 未過期時回傳原 token")
        void shouldReturnTokenWhenNotExpired() {
            User user = new User();
            RefreshToken token = new RefreshToken(user, "valid-token", LocalDateTime.now().plusDays(1));

            RefreshToken result = refreshTokenService.verifyExpiration(token);

            assertThat(result).isSameAs(token);
            verify(refreshTokenRepository, never()).delete(any());
        }

        @Test
        @DisplayName("應該在 token 已過期時刪除並拋出 TokenRefreshException")
        void shouldDeleteAndThrowWhenExpired() {
            User user = new User();
            RefreshToken token = new RefreshToken(user, "expired-token", LocalDateTime.now().minusSeconds(1));

            assertThatThrownBy(() -> refreshTokenService.verifyExpiration(token))
                    .isInstanceOf(TokenRefreshException.class);

            verify(refreshTokenRepository).delete(token);
        }
    }

    // ─── deleteByUserId ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteByUserId")
    class DeleteByUserIdTests {

        @Test
        @DisplayName("應該在用戶不存在時拋出 EntityNotFoundException")
        void shouldThrowWhenUserNotFound() {
            String userId = UUID.randomUUID().toString();
            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.deleteByUserId(userId))
                    .isInstanceOf(EntityNotFoundException.class);

            verify(refreshTokenRepository, never()).deleteByUser(any());
        }

        @Test
        @DisplayName("應該在用戶存在時刪除 token 並回傳刪除筆數")
        void shouldDeleteTokenAndReturnCount() {
            String userId = UUID.randomUUID().toString();
            User user = new User();

            when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));
            when(refreshTokenRepository.deleteByUser(user)).thenReturn(1);

            int result = refreshTokenService.deleteByUserId(userId);

            assertThat(result).isEqualTo(1);
            verify(refreshTokenRepository).deleteByUser(user);
        }
    }
}

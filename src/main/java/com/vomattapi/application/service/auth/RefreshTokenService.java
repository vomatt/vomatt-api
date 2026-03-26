package com.vomattapi.application.service.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.RefreshToken;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.user.repository.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${app.jwt.refreshExpiration}")
    private Long refreshTokenDurationMs;

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Transactional // 添加事务注解确保删除操作在事务上下文中执行
    public RefreshToken createRefreshToken(String userId) {
        RefreshToken refreshToken = new RefreshToken();

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        // Check if the member already has a refresh token - if so, delete it
        refreshTokenRepository.deleteByUser(user);

        refreshToken.setUser(user);
        
        // Convert milliseconds to seconds and add to LocalDateTime
        long seconds = refreshTokenDurationMs / 1000;
        refreshToken.setExpiryDate(LocalDateTime.now().plusSeconds(seconds));
        
        refreshToken.setToken(UUID.randomUUID().toString());

        refreshToken = refreshTokenRepository.save(refreshToken);
        return refreshToken;
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(token);
            throw new TokenRefreshException(token.getToken(),
                    "Refresh token was expired. Please make a new signin request");
        }

        return token;
    }

    @Transactional
    public int deleteByUserId(String userId) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
        return refreshTokenRepository.deleteByUser(user);
    }
}
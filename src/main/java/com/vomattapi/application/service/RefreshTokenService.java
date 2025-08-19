package com.vomattapi.application.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.RefreshToken;
import com.vomattapi.domain.member.repository.MemberRepository;
import com.vomattapi.domain.member.repository.RefreshTokenRepository;

@Service
public class RefreshTokenService {
    @Value("${app.jwt.refreshExpiration}")
    private Long refreshTokenDurationMs;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private MemberRepository memberRepository;

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Transactional // 添加事务注解确保删除操作在事务上下文中执行
    public RefreshToken createRefreshToken(String userId) {
        RefreshToken refreshToken = new RefreshToken();

        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Member not found with id: " + userId));

        // Check if the member already has a refresh token - if so, delete it
        refreshTokenRepository.deleteByMember(member);

        refreshToken.setMember(member);
        
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
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Member not found with id: " + userId));
        return refreshTokenRepository.deleteByMember(member);
    }
}
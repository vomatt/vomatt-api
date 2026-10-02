package com.vomatt.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class JwtUtil {

    private final SecretKey key;
    private final long expirationSeconds;

    // HS256 需要 ≥256 bits（32 bytes）的密鑰；對齊 JWT_SECRET ≥32 字元規範
    private static final int MIN_SECRET_BYTES = 32;

    public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        // 啟動時（bean 建立）就驗證密鑰長度，於組態錯誤時 fail-fast，
        // 而非等到第一次簽發/驗證 token 時才由 JJWT 隱性失敗
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 過短：HS256 需至少 " + MIN_SECRET_BYTES + " bytes（256 bits），目前僅 "
                            + secretBytes.length + " bytes。請設定足夠長的 JWT_SECRET（≥32 字元）。");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(String userId, String email, List<String> roles) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder().subject(userId).claim("email", email).claim("roles", roles).issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + expirationSeconds * 1000)).signWith(key).compact();
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal parseToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        return new UserPrincipal(claims.getSubject(), claims.get("email", String.class),
                (List<String>) claims.get("roles"));
    }

    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(authToken);
            return true;
        } catch (SignatureException e) {
            log.error("Invalid JWT signature: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
        }

        return false;
    }
}

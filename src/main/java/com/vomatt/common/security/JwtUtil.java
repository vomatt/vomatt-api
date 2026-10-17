package com.vomatt.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
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
    // JwtParser is immutable and thread-safe; build it once instead of per request
    private final JwtParser parser;
    private final long expirationSeconds;

    // HS256 needs a key of >= 256 bits (32 bytes); matches the JWT_SECRET >= 32 characters rule
    private static final int MIN_SECRET_BYTES = 32;

    public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        // Validate the key length at startup (bean creation) to fail fast on misconfiguration,
        // instead of JJWT failing implicitly on the first token sign/verify
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret is too short: HS256 needs at least " + MIN_SECRET_BYTES + " bytes (256 bits), got only "
                            + secretBytes.length + " bytes. Set a long enough JWT_SECRET (>= 32 characters).");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.parser = Jwts.parser().verifyWith(key).build();
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(String userId, String email, List<String> roles) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder().subject(userId).claim("email", email).claim("roles", roles).issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + expirationSeconds * 1000)).signWith(key).compact();
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal parseToken(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        return new UserPrincipal(claims.getSubject(), claims.get("email", String.class),
                (List<String>) claims.get("roles"));
    }

    public boolean validateJwtToken(String authToken) {
        try {
            parser.parseSignedClaims(authToken);
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

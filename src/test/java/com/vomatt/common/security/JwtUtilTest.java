package com.vomatt.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "jwt-util-test-secret-0123456789-0123456789";
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 900);

    @Test
    void shouldCarryOnlySubjectRolesScopeAndJtiWhenTokenIsIssued() {
        String token = jwtUtil.generateToken("user-1", List.of("user", "admin"), false);

        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.get("scope")).isEqualTo("user");
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "jti", "roles", "scope", "iat", "exp");

        UserPrincipal principal = jwtUtil.parseToken(token);
        assertThat(principal.userId()).isEqualTo("user-1");
        // Without the admin scope the admin role is dropped
        assertThat(principal.roles()).containsExactly("user");
        assertThat(jwtUtil.parseToken(jwtUtil.generateToken("user-1", List.of("admin"), true)).grantsAdmin()).isTrue();
    }

    @Test
    void shouldVerifyTokensOfEveryConfiguredKeyWhileSigningWithTheFirst() {
        String oldSecret = "jwt-util-test-old-secret-0123456789-0123456789";
        JwtUtil before = new JwtUtil(oldSecret, 900);
        JwtUtil rotated = new JwtUtil(SECRET + ", " + oldSecret, 900);
        String signedByOld = before.generateToken("user-2", List.of("user"), false);
        String signedByNew = rotated.generateToken("user-2", List.of("user"), false);

        assertThat(rotated.parseToken(signedByOld).userId()).isEqualTo("user-2");
        assertThat(rotated.parseToken(signedByNew).userId()).isEqualTo("user-2");
        // The header names the key, and the first key is the one that signs
        assertThat(Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(signedByNew).getHeader().getKeyId())
                .isEqualTo(AppKey.sha256Hex(SECRET).substring(0, 8));
        // Once the old key is dropped its tokens are refused
        assertThat(new JwtUtil(SECRET, 900).validateJwtToken(signedByOld)).isFalse();
        assertThatThrownBy(() -> new JwtUtil(SECRET + ",short", 900)).isInstanceOf(IllegalStateException.class);
    }
}

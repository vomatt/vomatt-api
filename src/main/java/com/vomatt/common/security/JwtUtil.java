package com.vomatt.common.security;

import com.vomatt.common.constant.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

    // The first configured secret signs; every configured secret verifies, picked by the token's kid header
    private final SecretKey signingKey;
    private final String signingKid;
    private final java.util.Map<String, SecretKey> keysByKid;
    // JwtParser is immutable and thread-safe; build it once instead of per request
    private final JwtParser parser;
    private final long expirationSeconds;

    // HS256 needs a key of >= 256 bits (32 bytes); matches the JWT_SECRET >= 32 characters rule
    private static final int MIN_SECRET_BYTES = 32;
    private static final int KID_LENGTH = 8;

    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE_ADMIN = "admin";
    private static final String SCOPE_USER = "user";
    private static final String SESSION_CLAIM = "sid";

    /**
     * @param secret one or more HS256 secrets, comma-separated. The first one signs new tokens and all of them verify,
     *               so a key is rotated without a cut: prepend the new secret, deploy, wait out
     *               {@code jwt.expiration-seconds}, then drop the old one and deploy again. Each key is named by
     *               {@code kid = SHA-256(secret)[0..8)} in the token header; a token without kid (issued before kids
     *               existed) is checked against the signing key only.
     */
    public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        // Validate the key lengths at startup (bean creation) to fail fast on misconfiguration,
        // instead of JJWT failing implicitly on the first token sign/verify
        java.util.Map<String, SecretKey> keys = new java.util.LinkedHashMap<>();
        for (String one : (secret == null ? "" : secret).split(",")) {
            byte[] secretBytes = one.strip().getBytes(StandardCharsets.UTF_8);
            if (secretBytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "jwt.secret is too short: HS256 needs at least " + MIN_SECRET_BYTES + " bytes (256 bits), got only "
                                + secretBytes.length + " bytes. Set a long enough JWT_SECRET (>= 32 characters per key).");
            }
            keys.put(AppKey.sha256Hex(one.strip()).substring(0, KID_LENGTH), Keys.hmacShaKeyFor(secretBytes));
        }
        this.keysByKid = java.util.Map.copyOf(keys);
        this.signingKid = keys.keySet().iterator().next();
        this.signingKey = keys.get(signingKid);
        this.parser = Jwts.parser().keyLocator(header -> {
            String kid = header instanceof io.jsonwebtoken.ProtectedHeader p ? p.getKeyId() : null;
            // Unknown kid -> null -> JJWT refuses the token
            return kid == null ? signingKey : keysByKid.get(kid);
        }).build();
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * @param adminScope true only for tokens issued by the admin console login; other tokens never grant the admin
     *                   role, even when the account holds it
     * @param sessionId  the refresh session the token belongs to (see {@link RefreshTokenService}), checked against
     *                   its revocation on every request
     */
    public String generateToken(String userId, List<String> roles, boolean adminScope, String sessionId) {
        long nowMs = System.currentTimeMillis();
        // Payload is sub / roles / scope / sid / jti only: no email, since the token transits proxies, APM and browser
        // storage that no PII should reach
        return Jwts.builder().header().keyId(signingKid).and()
                .subject(userId).id(java.util.UUID.randomUUID().toString()).claim("roles", roles)
                .claim(SCOPE_CLAIM, adminScope ? SCOPE_ADMIN : SCOPE_USER).claim(SESSION_CLAIM, sessionId).issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + expirationSeconds * 1000)).signWith(signingKey).compact();
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal parseToken(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        List<String> roles = (List<String>) claims.get("roles");
        // Fails closed: a token without the admin scope (including ones issued before the claim existed) drops the
        // admin role here, so nothing downstream can grant it
        if (roles != null && !SCOPE_ADMIN.equals(claims.get(SCOPE_CLAIM, String.class))) {
            roles = roles.stream().filter(r -> !UserRole.isAdmin(r)).toList();
        }
        return new UserPrincipal(claims.getSubject(), roles, claims.get(SESSION_CLAIM, String.class));
    }
}

package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    public static final String AUTH_ERROR_ATTR = JwtAuthFilter.class.getName() + ".authError";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final RequestMatcher ADMIN_PATHS = anyOf(SecurityEndpoints.AUTHENTICATED_ADMIN);
    private static final RequestMatcher PUBLIC_AUTH_PATHS = anyOf(SecurityEndpoints.PUBLIC_AUTH);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SecurityErrorWriter errorWriter;
    private final RefreshTokenService refreshTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        UserPrincipal principal = null;
        if (StringUtils.hasText(token)) {
            try {
                principal = jwtUtil.parseToken(token);
                if (sessionRevoked(principal)) {
                    // Like an invalid token: public paths go on anonymous, the entry point answers 401 elsewhere
                    request.setAttribute(AUTH_ERROR_ATTR, MessageKey.AUTH_SESSION_REVOKED);
                    principal = null;
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Reason is consumed by JwtAuthenticationEntryPoint only if the path requires auth
                request.setAttribute(AUTH_ERROR_ATTR, ex instanceof ExpiredJwtException
                        ? MessageKey.AUTH_TOKEN_EXPIRED : MessageKey.AUTH_TOKEN_INVALID);
                log.debug("Invalid JWT token: {}", ex.getMessage());
            }
            if (principal != null) {
                var authorities = principal.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT)))
                        .collect(Collectors.toList());
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        // Roles outlive a demotion, and access tokens a suspension or deletion. On /api/admin/** (matched on the decoded
        // path like the security rules, so an encoded /api/%61dmin/... cannot skip it) a token granting the admin role
        // re-checks the account on every call, reads included; other tokens are left to the role rule, no DB hit.
        // Elsewhere only writes re-check; the public auth endpoints keep their own handling (login and refresh
        // already reject suspended users)
        boolean adminPath = principal != null && ADMIN_PATHS.matches(request);
        // grantsAdmin uses the same upper-casing as the authorities above
        boolean recheck = adminPath ? principal.grantsAdmin()
                : principal != null && !READ_METHODS.contains(request.getMethod()) && !PUBLIC_AUTH_PATHS.matches(request);
        if (recheck) {
            UUID userId = UUID.fromString(principal.userId());
            if (adminPath && userRepository.isActiveAdmin(userId)) {
                filterChain.doFilter(request, response);
                return;
            }
            // Empty is a deleted account, false a suspended one
            Optional<Boolean> active = userRepository.findActiveById(userId);
            if (active.isEmpty()) {
                errorWriter.write(request, response, HttpServletResponse.SC_NOT_FOUND, MessageKey.USER_NOT_FOUND);
                return;
            }
            if (!active.get()) {
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.AUTH_ACCOUNT_BANNED);
                return;
            }
            if (adminPath) {
                // Token still claims admin, database says demoted
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.COMMON_FORBIDDEN);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * A session kicked from another device (or revoked by suspension, reuse detection, role change) cuts its access
     * tokens at once instead of when they expire. Tokens issued before the sid claim carry none and pass. A Redis
     * failure fails open, like the rate limiter: the token is still signed and short-lived.
     */
    private boolean sessionRevoked(UserPrincipal principal) {
        if (principal.sessionId() == null) {
            return false;
        }
        try {
            return refreshTokenService.isSessionRevoked(principal.sessionId());
        } catch (RedisOperationException ex) {
            log.warn("Session revocation check skipped (Redis unavailable): {}", ex.getMessage());
            return false;
        }
    }

    private static RequestMatcher anyOf(String[] patterns) {
        return new OrRequestMatcher(Arrays.stream(patterns)
                .map(PathPatternRequestMatcher.withDefaults()::matcher).map(RequestMatcher.class::cast).toList());
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

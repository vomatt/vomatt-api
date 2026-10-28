package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    public static final String AUTH_ERROR_ATTR = JwtAuthFilter.class.getName() + ".authError";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final RequestMatcher ADMIN_PATHS = new OrRequestMatcher(Arrays.stream(SecurityEndpoints.AUTHENTICATED_ADMIN)
            .map(PathPatternRequestMatcher.withDefaults()::matcher).map(RequestMatcher.class::cast).toList());

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        UserPrincipal principal = null;
        if (StringUtils.hasText(token)) {
            try {
                principal = jwtUtil.parseToken(token);
                var authorities = principal.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT)))
                        .collect(Collectors.toList());
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ex) {
                // Reason is consumed by JwtAuthenticationEntryPoint only if the path requires auth
                request.setAttribute(AUTH_ERROR_ATTR, ex instanceof ExpiredJwtException
                        ? MessageKey.AUTH_TOKEN_EXPIRED : MessageKey.AUTH_TOKEN_INVALID);
                log.debug("Invalid JWT token: {}", ex.getMessage());
            }
        }
        // Admin roles outlive a demotion or suspension in the token, so every /api/admin/** call (reads included) with a
        // token granting ROLE_ADMIN (same upper-casing as the authorities above) re-checks the account in one query;
        // other tokens are refused by the security rule. Matched on the decoded path like the security rules, so an
        // encoded /api/%61dmin/... cannot skip the check
        boolean adminCall = principal != null && ADMIN_PATHS.matches(request) && principal.grantsAdmin();
        if (adminCall && !userRepository.isActiveAdmin(UUID.fromString(principal.userId()))) {
            // Second query only on refusal: suspended gets the same code as other writes, demoted the plain 403
            UUID userId = UUID.fromString(principal.userId());
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    userRepository.existsByIdAndActiveFalse(userId) ? MessageKey.AUTH_ACCOUNT_BANNED : MessageKey.COMMON_FORBIDDEN);
            return;
        }
        // Access tokens outlive a suspension or account deletion, so writes re-check the account; reads skip the DB hit.
        // /api/auth/** keeps its own handling (login and refresh already reject suspended users).
        if (!adminCall && principal != null && !READ_METHODS.contains(request.getMethod())
                && !request.getRequestURI().startsWith("/api/auth/")
                && !userRepository.existsByIdAndActiveTrue(UUID.fromString(principal.userId()))) {
            // Second query only on refusal: suspended gets the banned code, a deleted account user.not_found
            if (userRepository.existsByIdAndActiveFalse(UUID.fromString(principal.userId()))) {
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.AUTH_ACCOUNT_BANNED);
            } else {
                errorWriter.write(request, response, HttpServletResponse.SC_NOT_FOUND, MessageKey.USER_NOT_FOUND);
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

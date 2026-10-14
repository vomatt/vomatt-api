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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    public static final String AUTH_ERROR_ATTR = JwtAuthFilter.class.getName() + ".authError";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

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
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
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
        // Access tokens outlive a suspension, so writes re-check users.active; reads skip the DB hit.
        // /api/auth/** keeps its own handling (login and refresh already reject suspended users).
        if (principal != null && !READ_METHODS.contains(request.getMethod())
                && !request.getRequestURI().startsWith("/api/auth/")
                && userRepository.existsByIdAndActiveFalse(UUID.fromString(principal.userId()))) {
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.AUTH_ACCOUNT_BANNED);
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

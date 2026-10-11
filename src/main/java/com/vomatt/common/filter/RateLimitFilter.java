package com.vomatt.common.filter;

import com.vomatt.common.config.RateLimitConfig;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.SecurityErrorWriter;
import com.vomatt.common.util.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisService redisService;
    private final RateLimitConfig rateLimitConfig;
    private final SecurityErrorWriter errorWriter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!rateLimitConfig.isEnabled()) return true;
        String uri = request.getRequestURI();
        return rateLimitConfig.getPaths().stream().noneMatch(p -> pathMatcher.match(p, uri));
    }

    /** Auth endpoint patterns; these fail closed when Redis is down (prevents OTP brute force). */
    private static final String AUTH_PATH_PATTERN = "/api/auth/**";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimitConfig.Limit limit = rateLimitConfig.getLimit();
        long now = Instant.now().getEpochSecond();
        Long count = countRequest(request, now / limit.getWindowSeconds(), limit);
        if (count == null) {
            // P5-#4: when Redis is down, auth endpoints fail closed (blocked with 429) to prevent OTP brute force;
            // other endpoints fail open (allowed) to preserve availability.
            // No Retry-After here: waiting does not bring Redis back.
            if (pathMatcher.match(AUTH_PATH_PATTERN, request.getRequestURI())) {
                errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(), MessageKey.COMMON_RATE_LIMITED);
                return;
            }
        } else if (count > limit.getCapacity()) {
            // Fixed-window counter: the limit resets when the counted window ends
            long retryAfter = limit.getWindowSeconds() - now % limit.getWindowSeconds();
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(), MessageKey.COMMON_RATE_LIMITED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Increments the client's counter for {@code window}; returns null when Redis is unavailable. */
    private Long countRequest(HttpServletRequest request, long window, RateLimitConfig.Limit limit) {
        try {
            return redisService.incrementAndExpire("rate:limit", ClientIpResolver.resolve(request) + ":" + window, limit.ttl());
        } catch (Exception e) {
            log.warn("Rate limit check failed for uri={}: {}", request.getRequestURI(), e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the real client IP (P1-#6).
     *
     * <p>This service runs behind the platform proxy (Zeabur), and {@code X-Forwarded-For} is appended by the trusted edge.
     * Take the <strong>rightmost</strong> non-empty IP: the client can forge the left segments but cannot control the rightmost one appended by the edge,
     * so using the rightmost prevents bypassing rate limits with a fake IP per request. Without XFF, fall back to {@code remoteAddr}.</p>
     */
}

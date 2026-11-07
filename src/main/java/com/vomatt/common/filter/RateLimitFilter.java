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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisService redisService;
    private final RateLimitConfig rateLimitConfig;
    private final SecurityErrorWriter errorWriter;
    /**
     * Matchers on the parsed, decoded path that SecurityConfig and Spring MVC route on: matching the raw request URI
     * let /api/%61uth/send-otp reach the auth handler uncounted. Configured paths are cached per pattern.
     */
    private static final PathPatternRequestMatcher.Builder MATCHERS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher AUTH_PATHS = MATCHERS.matcher("/api/auth/**");
    /** Anonymous writes with no other limit; also fail closed when Redis is down. */
    private static final RequestMatcher CONTACT_PATHS = MATCHERS.matcher("/api/contact/**");
    private final Map<String, RequestMatcher> pathMatchers = new ConcurrentHashMap<>();

    private boolean matches(String pattern, HttpServletRequest request) {
        return pathMatchers.computeIfAbsent(pattern, MATCHERS::matcher).matches(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!rateLimitConfig.isEnabled()) return true;
        // Outside auth only writes count (Ballots, Comments, likes...); reads stay unlimited
        if (SKIPPED_METHODS.contains(request.getMethod()) && !AUTH_PATHS.matches(request)) return true;
        return rateLimitConfig.getPaths().stream().noneMatch(p -> matches(p, request));
    }



    // OPTIONS is listed defensively: Spring Security's CorsFilter normally answers preflights before this filter runs
    private static final Set<String> SKIPPED_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimitConfig.Limit limit = rateLimitConfig.getLimit();
        long now = Instant.now().getEpochSecond();
        Long count = countRequest(request, now / limit.getWindowSeconds(), limit);
        if (count == null) {
            // When Redis is down, auth endpoints fail closed (blocked with 429) to prevent OTP brute force, and so does
            // the anonymous contact form, whose daily limit also lives in Redis; other endpoints fail open (allowed) to
            // preserve availability. No Retry-After here: waiting does not bring Redis back.
            if (AUTH_PATHS.matches(request) || CONTACT_PATHS.matches(request)) {
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
            // Writes get their own bucket so heavy voting cannot lock an IP out of /api/auth/refresh
            String prefix = AUTH_PATHS.matches(request) ? "rate:limit" : "rate:limit:write";
            return redisService.incrementAndExpire(prefix, ClientIpResolver.resolve(request) + ":" + window, limit.ttl());
        } catch (Exception e) {
            log.warn("Rate limit check failed for uri={}: {}", request.getRequestURI(), e.getMessage());
            return null;
        }
    }
}

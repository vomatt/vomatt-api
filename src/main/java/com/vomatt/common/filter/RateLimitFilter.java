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

    /** 認證端點 pattern；Redis 故障時此類端點 fail-closed（防 OTP 暴力破解）。 */
    private static final String AUTH_PATH_PATTERN = "/api/auth/**";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isRateLimited(request)) {
            int windowSeconds = rateLimitConfig.getLimit().getWindowSeconds();
            // Fixed-window counter: the limit resets when the current window ends
            long retryAfter = windowSeconds - Instant.now().getEpochSecond() % windowSeconds;
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    MessageKey.COMMON_RATE_LIMITED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isRateLimited(HttpServletRequest request) {
        String ip = ClientIpResolver.resolve(request);
        try {
            RateLimitConfig.Limit limit = rateLimitConfig.getLimit();
            long window = Instant.now().getEpochSecond() / limit.getWindowSeconds();
            long count = redisService.incrementAndExpire("rate:limit", ip + ":" + window, limit.ttl());
            return count > limit.getCapacity();
        } catch (Exception e) {
            // P5-#4：Redis 故障時，認證端點 fail-closed（擋下，回 429）避免 OTP 暴力破解；
            // 其餘端點維持 fail-open（放行）以保可用性。
            boolean authEndpoint = pathMatcher.match(AUTH_PATH_PATTERN, request.getRequestURI());
            log.warn("Rate limit check failed, failing {} for uri={}: {}",
                    authEndpoint ? "CLOSED" : "open", request.getRequestURI(), e.getMessage());
            return authEndpoint;
        }
    }

    /**
     * 解析真實 client IP（P1-#6）。
     *
     * <p>本服務部署在平台代理（Zeabur）後，{@code X-Forwarded-For} 由可信 edge 附加。
     * 取<strong>最右側</strong>非空 IP——client 可任意偽造左側段落，但無法控制 edge 附加在最右的那一段，
     * 故以最右側為準可防「每請求換假 IP 繞過限流」。無 XFF 時退回 {@code remoteAddr}。</p>
     */
}

package com.vomatt.common.filter;

import com.vomatt.common.config.RateLimitConfig;
import com.vomatt.common.redis.RedisOperationException;
import com.vomatt.common.redis.RedisService;
import com.vomatt.common.security.SecurityTestSupport;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RedisService redisService;

    private RateLimitConfig config;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setPaths(List.of("/api/auth/**"));
        config.setLimit(new RateLimitConfig.Limit(300, 60));
        filter = new RateLimitFilter(redisService, config, SecurityTestSupport.errorWriter());
    }

    @Test
    void whenUnderLimit_shouldPassThrough() throws Exception {
        when(redisService.incrementAndExpire(eq("rate:limit"), anyString(), any(Duration.class))).thenReturn(1L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void whenOverLimit_shouldReturn429() throws Exception {
        when(redisService.incrementAndExpire(eq("rate:limit"), anyString(), any(Duration.class))).thenReturn(301L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("請求過於頻繁");
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"common.rate_limited\"");
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 60L);
    }

    @Test
    void shouldReturnEnglishMessageWhenAcceptLanguageIsEn() throws Exception {
        when(redisService.incrementAndExpire(eq("rate:limit"), anyString(), any(Duration.class))).thenReturn(301L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        request.addHeader("Accept-Language", "en");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getContentAsString()).contains("Too many requests");
    }

    @Test
    void whenPathNotInConfig_shouldSkipFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/votes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(redisService);
    }

    @Test
    void whenSwaggerPath_shouldSkipFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(redisService);
    }

    @Test
    void whenFilterDisabled_shouldSkipAll() throws Exception {
        config.setEnabled(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(redisService);
    }

    @Test
    void whenRedisDownOnAuthEndpoint_shouldFailClosed() throws Exception {
        // P5-#4：Redis 故障時認證端點改 fail-closed，避免 OTP 暴力破解
        when(redisService.incrementAndExpire(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisOperationException("Redis down", null));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void whenRedisDownOnNonAuthEndpoint_shouldFailOpen() throws Exception {
        // 非認證端點維持 fail-open，Redis 故障不影響可用性
        config.setPaths(List.of("/api/auth/**", "/api/sensitive/**"));
        when(redisService.incrementAndExpire(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisOperationException("Redis down", null));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sensitive/op");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void whenXForwardedForPresent_shouldUseLastSegment() throws Exception {
        // P1-#6：取最右側（平台 edge 附加）IP，client 無法靠偽造左側 XFF 繞過限流
        when(redisService.incrementAndExpire(anyString(), anyString(), any(Duration.class))).thenReturn(1L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/send-otp");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(redisService).incrementAndExpire(eq("rate:limit"),
                org.mockito.ArgumentMatchers.startsWith("5.6.7.8:"), any(Duration.class));
    }
}

package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityErrorHandlersTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private final SecurityErrorWriter writer = SecurityTestSupport.errorWriter();
    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(writer);
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 3600);
    private final JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtUtil);

    private MockHttpServletResponse commence(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, new InsufficientAuthenticationException("x"));
        return response;
    }

    private MockHttpServletRequest requestWithBearer(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);
        jwtAuthFilter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        SecurityContextHolder.clearContext();
        return request;
    }

    @Test
    @DisplayName("shouldReturn401UnauthorizedWhenNoToken")
    void shouldReturn401UnauthorizedWhenNoToken() throws Exception {
        MockHttpServletResponse response = commence(new MockHttpServletRequest("GET", "/api/users/me"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"success\":false", "\"errorCode\":\"common.unauthorized\"", "未授權的請求");
    }

    @Test
    void shouldReturn401TokenExpiredWhenTokenExpired() throws Exception {
        String expired = Jwts.builder().subject("u1").claim("roles", List.of("user"))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();

        MockHttpServletResponse response = commence(requestWithBearer(expired));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"auth.token.expired\"");
    }

    @Test
    void shouldReturn401TokenInvalidWhenTokenMalformed() throws Exception {
        MockHttpServletResponse response = commence(requestWithBearer("not-a-jwt"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"auth.token.invalid\"");
    }

    @Test
    void shouldReturn401TokenInvalidWhenSignatureWrong() throws Exception {
        String forged = Jwts.builder().subject("u1").claim("roles", List.of("admin"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-12345".getBytes())).compact();

        MockHttpServletResponse response = commence(requestWithBearer(forged));

        assertThat(response.getContentAsString()).contains("\"errorCode\":\"auth.token.invalid\"");
    }

    @Test
    void shouldNotSetErrorAttributeWhenTokenValid() throws Exception {
        String token = jwtUtil.generateToken("u1", "a@b.c", List.of("user"));

        assertThat(requestWithBearer(token).getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR)).isNull();
    }

    @Test
    void shouldReturn403ForbiddenWhenRoleMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAccessDeniedHandler(writer).handle(new MockHttpServletRequest("GET", "/api/admin/tags"), response,
                new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"success\":false", "\"errorCode\":\"common.forbidden\"", "沒有權限執行此操作");
    }

    @Test
    void shouldLocalizeMessageByAcceptLanguage() throws Exception {
        MockHttpServletRequest en = new MockHttpServletRequest("GET", "/api/users/me");
        en.addHeader("Accept-Language", "en");
        MockHttpServletRequest zhTw = new MockHttpServletRequest("GET", "/api/users/me");
        zhTw.addHeader("Accept-Language", "zh-TW");

        assertThat(commence(en).getContentAsString()).contains("Unauthorized request");
        assertThat(commence(zhTw).getContentAsString()).contains("未授權的請求");
        assertThat(MessageKey.COMMON_RATE_LIMITED.code()).isEqualTo("common.rate_limited");
    }

    @Test
    void shouldDisableSwaggerWhenSwaggerEnabledNotSet() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0));

        assertThat(env.resolvePlaceholders("${springdoc.api-docs.enabled}")).isEqualTo("false");
        assertThat(env.resolvePlaceholders("${springdoc.swagger-ui.enabled}")).isEqualTo("false");
    }
}

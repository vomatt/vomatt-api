package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
import com.vomatt.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityErrorHandlersTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private final SecurityErrorWriter writer = SecurityTestSupport.errorWriter();
    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(writer);
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 3600);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtUtil, userRepository, writer);

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
    void shouldPropagateWhenTokenParsingHitsServerBug() {
        // A server-side bug must surface as an error, not as "sign in again"
        JwtUtil brokenJwtUtil = mock(JwtUtil.class);
        when(brokenJwtUtil.parseToken("token")).thenThrow(new IllegalStateException("bug"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer token");

        assertThatThrownBy(() -> new JwtAuthFilter(brokenJwtUtil, userRepository, writer)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR)).isNull();
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
        String token = jwtUtil.generateToken("u1", List.of("user"), false);

        assertThat(requestWithBearer(token).getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR)).isNull();
    }

    private MockHttpServletResponse filterAs(UUID userId, String method, String uri, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken(userId.toString(), List.of("user"), false));
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwtAuthFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();
        return response;
    }

    @Test
    void shouldReturn403AccountBannedWhenSuspendedUserCastsBallot() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findActiveById(userId)).thenReturn(Optional.of(false));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = filterAs(userId, "POST", "/api/votes/" + UUID.randomUUID() + "/vote", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"success\":false", "\"errorCode\":\"" + MessageKey.AUTH_ACCOUNT_BANNED.code() + "\"");
        verifyNoInteractions(chain);
    }

    @Test
    void shouldSkipActiveCheckWhenRead() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = filterAs(UUID.randomUUID(), "GET", "/api/users/me", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldPassThroughWhenActiveUserWrites() throws Exception {
        UUID userId = UUID.randomUUID();
        FilterChain chain = mock(FilterChain.class);

        when(userRepository.findActiveById(userId)).thenReturn(Optional.of(true));

        MockHttpServletResponse response = filterAs(userId, "POST", "/api/votes/" + UUID.randomUUID() + "/vote", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verify(userRepository).findActiveById(userId);
    }

    @Test
    void shouldReturn404UserNotFoundWhenDeletedUserWrites() throws Exception {
        UUID userId = UUID.randomUUID();
        FilterChain chain = mock(FilterChain.class);

        // An access token outlives account deletion; the account is neither active nor suspended
        MockHttpServletResponse response = filterAs(userId, "PUT", "/api/votes/" + UUID.randomUUID() + "/bookmark", chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString())
                .contains("\"success\":false", "\"errorCode\":\"" + MessageKey.USER_NOT_FOUND.code() + "\"");
        verifyNoInteractions(chain);
    }

    @Test
    void shouldDropAdminRoleWhenTokenLacksAdminScope() {
        String userToken = jwtUtil.generateToken("u1", List.of("user", "admin"), false);
        String adminToken = jwtUtil.generateToken("u1", List.of("user", "admin"), true);

        assertThat(jwtUtil.parseToken(userToken).roles()).containsExactly("user");
        assertThat(jwtUtil.parseToken(adminToken).roles()).containsExactly("user", "admin");
    }

    private MockHttpServletResponse filterAsAdmin(UUID userId, String method, String uri, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken(userId.toString(), List.of("user", "admin"), true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwtAuthFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();
        return response;
    }

    @ParameterizedTest(name = "{0} by {1} token, account {2} -> {3} {4}")
    @CsvSource(nullValues = "-", value = {
            // method, token, account (deleted | suspended | active | admin), status, errorCode
            "GET,  user,  deleted,   404, user.not_found",
            "POST, user,  deleted,   404, user.not_found",
            "GET,  admin, deleted,   404, user.not_found",
            "POST, admin, deleted,   404, user.not_found",
            "GET,  user,  suspended, 403, auth.account_banned",
            "POST, user,  suspended, 403, auth.account_banned",
            "GET,  admin, suspended, 403, auth.account_banned",
            "POST, admin, suspended, 403, auth.account_banned",
            "GET,  user,  active,    403, common.forbidden",
            "POST, user,  active,    403, common.forbidden",
            // Token still claims admin, database says demoted
            "GET,  admin, active,    403, common.forbidden",
            "POST, admin, active,    403, common.forbidden",
            "GET,  admin, admin,     200, -",
            "POST, admin, admin,     200, -",
    })
    void shouldDecideAdminPathByAccountStateAndRoleWhenReadOrWrite(
            String method, String token, String account, int status, String errorCode) throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.isActiveAdmin(userId)).thenReturn(account.equals("admin"));
        when(userRepository.findActiveById(userId)).thenReturn(switch (account) {
            case "deleted" -> Optional.empty();
            case "suspended" -> Optional.of(false);
            default -> Optional.of(true);
        });
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = token.equals("admin")
                ? filterAsAdmin(userId, method, "/api/admin/users", chain)
                : filterAs(userId, method, "/api/admin/users", chain);

        assertThat(response.getStatus()).isEqualTo(status);
        if (errorCode == null) {
            verify(chain).doFilter(any(), any());
        } else {
            assertThat(response.getContentAsString()).contains("\"errorCode\":\"" + errorCode + "\"");
            verifyNoInteractions(chain);
        }
    }

    @Test
    void shouldReturn403WhenDemotedAdminUsesEncodedAdminPath() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findActiveById(userId)).thenReturn(Optional.of(true));
        FilterChain chain = mock(FilterChain.class);

        // %61 decodes to "a": the security rules and MVC see /api/admin/users
        MockHttpServletResponse response = filterAsAdmin(userId, "GET", "/api/%61dmin/users", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(userRepository).isActiveAdmin(userId);
        verifyNoInteractions(chain);
    }

    @Test
    void shouldCheckAdminOnceWhenActiveAdminWrites() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.isActiveAdmin(userId)).thenReturn(true);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = filterAsAdmin(userId, "DELETE", "/api/admin/votes/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verify(userRepository, never()).findActiveById(any());
    }

    @Test
    void shouldCheckDatabaseWhenTokenClaimsDifferentRoleCase() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findActiveById(userId)).thenReturn(Optional.of(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        // "ADMIN" still maps to ROLE_ADMIN, so it must go through the database check too
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken(userId.toString(), List.of("ADMIN"), true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        jwtAuthFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();

        assertThat(response.getStatus()).isEqualTo(403);
        verify(userRepository).isActiveAdmin(userId);
        verifyNoInteractions(chain);
    }

    @Test
    void shouldSkipActiveCheckWhenAuthEndpoint() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filterAs(UUID.randomUUID(), "POST", "/api/auth/refresh", chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldReturn403AccountBannedWhenSuspendedUserRegistersPasskey() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findActiveById(userId)).thenReturn(Optional.of(false));
        FilterChain chain = mock(FilterChain.class);

        // Signed-in auth writes are not exempt, only the public auth endpoints
        MockHttpServletResponse response = filterAs(userId, "POST", "/api/auth/passkey/register/options", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"" + MessageKey.AUTH_ACCOUNT_BANNED.code() + "\"");
        verifyNoInteractions(chain);
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
        // Only application.yml, so an exported SWAGGER_ENABLED in the shell cannot change the result
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0));
        PropertySourcesPropertyResolver env = new PropertySourcesPropertyResolver(sources);

        assertThat(env.resolvePlaceholders("${springdoc.api-docs.enabled}")).isEqualTo("false");
        assertThat(env.resolvePlaceholders("${springdoc.swagger-ui.enabled}")).isEqualTo("false");
    }

    /** Runs the filter on a public GET and returns the principal it left in the context (null when signed out). */
    private Object principalOnPublicPath(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/alice");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        try {
            jwtAuthFilter.doFilter(request, response, chain);
            // The request goes on to the permitAll rule; the filter writes no error itself
            verify(chain).doFilter(request, response);
            assertThat(response.getContentAsString()).isEmpty();
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? null : auth.getPrincipal();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldSetUserPrincipalWhenValidTokenOnPublicPath() throws Exception {
        String token = Jwts.builder().subject("u1").claim("roles", List.of("user"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();

        assertThat(principalOnPublicPath(token)).isInstanceOfSatisfying(UserPrincipal.class,
                p -> assertThat(p.userId()).isEqualTo("u1"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldContinueSignedOutWhenInvalidOrExpiredTokenOnPublicPath() throws Exception {
        String expired = Jwts.builder().subject("u1").claim("roles", List.of("user"))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();

        assertThat(principalOnPublicPath(null)).isNull();
        assertThat(principalOnPublicPath("not-a-jwt")).isNull();
        assertThat(principalOnPublicPath(expired)).isNull();
    }
}

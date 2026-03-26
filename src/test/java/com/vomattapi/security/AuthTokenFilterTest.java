package com.vomattapi.security;

import com.vomattapi.infrastructure.security.jwt.AuthTokenFilter;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.infrastructure.security.services.UserDetailsImpl;
import com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl;
import com.vomattapi.application.service.auth.JwtBlacklistService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenFilter")
class AuthTokenFilterTest {

    @Mock JwtUtils jwtUtils;
    @Mock UserDetailsServiceImpl userDetailsService;
    @Mock JwtBlacklistService jwtBlacklistService;
    @Mock FilterChain filterChain;

    @InjectMocks
    AuthTokenFilter filter;

    MockHttpServletRequest request;
    MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("有效 JWT 且未列入黑名單 → 應設定 Authentication")
    void shouldSetAuthWhenValidJwtNotBlacklisted() throws Exception {
        String token = "valid.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtUtils.validateJwtToken(token)).thenReturn(true);
        when(jwtBlacklistService.isTokenBlacklisted(token)).thenReturn(false);
        when(jwtUtils.getUserNameFromJwtToken(token)).thenReturn("testuser");

        UserDetailsImpl userDetails = new UserDetailsImpl(
                "user-id", "testuser", "test@example.com", "pw",
                true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("testuser");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("有效 JWT 但已列入黑名單 → 不應設定 Authentication")
    void shouldSkipAuthWhenJwtBlacklisted() throws Exception {
        String token = "blacklisted.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtUtils.validateJwtToken(token)).thenReturn(true);
        when(jwtBlacklistService.isTokenBlacklisted(token)).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("無效 JWT → 不應設定 Authentication")
    void shouldSkipAuthWhenJwtInvalid() throws Exception {
        String token = "invalid.jwt";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtUtils.validateJwtToken(token)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("無 Authorization 標頭 → 不應設定 Authentication")
    void shouldSkipAuthWhenNoHeader() throws Exception {
        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtUtils, never()).validateJwtToken(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 標頭不含 Bearer 前綴 → 不應設定 Authentication")
    void shouldSkipAuthWhenNotBearerToken() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtUtils, never()).validateJwtToken(any());
        verify(filterChain).doFilter(request, response);
    }
}

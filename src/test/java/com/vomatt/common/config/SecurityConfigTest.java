package com.vomatt.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void shouldExposeRetryAfterWhenServingCrossOriginResponses() {
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, null, new MockEnvironment());
        ReflectionTestUtils.setField(securityConfig, "corsAllowedOrigins", List.of("https://app.example.com"));

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/votes"));

        assertThat(cors.getExposedHeaders()).contains("Retry-After");
        // Bearer auth needs no cookies, so browsers are not told to send them
        assertThat(cors.getAllowCredentials()).isFalse();
    }

    @Test
    void shouldFailStartupWhenProdHasNoTrustedProxies() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, null, prod);

        assertThatThrownBy(() -> securityConfig.setTrustedProxies(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRUSTED_PROXIES");
        // Outside prod the blank value keeps the default list
        new SecurityConfig(null, null, null, null, new MockEnvironment()).setTrustedProxies("");
    }
}

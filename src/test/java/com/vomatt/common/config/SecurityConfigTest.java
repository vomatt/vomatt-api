package com.vomatt.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void shouldExposeRetryAfterWhenServingCrossOriginResponses() {
        SecurityConfig securityConfig = new SecurityConfig(null, null, null, null);
        ReflectionTestUtils.setField(securityConfig, "corsAllowedOrigins", List.of("https://app.example.com"));

        CorsConfiguration cors = securityConfig.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/votes"));

        assertThat(cors.getExposedHeaders()).contains("Retry-After");
    }
}

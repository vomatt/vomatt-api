package com.vomatt.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisConfigTest {

    @Test
    void shouldFailStartupWhenProdHasNoRedisPassword() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new RedisConfig(prod, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_PASSWORD");
        assertThatCode(() -> new RedisConfig(prod, "s3cret")).doesNotThrowAnyException();
        assertThatCode(() -> new RedisConfig(new MockEnvironment(), "")).doesNotThrowAnyException();
    }
}

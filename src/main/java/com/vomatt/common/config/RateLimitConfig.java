package com.vomatt.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConfigurationProperties("rate-limit")
@Getter
@Setter
public class RateLimitConfig {

    private boolean enabled = true;
    private Limit limit = new Limit(300, 60);
    private List<String> paths = List.of("/api/auth/**");

    @Getter
    @Setter
    public static class Limit {
        private int capacity;
        private int windowSeconds;

        public Limit() {}

        public Limit(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowSeconds = windowSeconds;
        }

        public Duration ttl() {
            return Duration.ofSeconds(windowSeconds * 2L);
        }
    }
}

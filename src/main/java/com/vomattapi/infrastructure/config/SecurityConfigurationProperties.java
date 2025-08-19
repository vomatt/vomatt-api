package com.vomattapi.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@ConfigurationProperties(prefix = "app.security")
@Component
@Data
public class SecurityConfigurationProperties {
    
    private Jwt jwt = new Jwt();
    private RateLimit rateLimit = new RateLimit();
    private Session session = new Session();
    
    @Data
    public static class Jwt {
        private Duration accessTokenExpiration = Duration.ofMinutes(15);
        private Duration refreshTokenExpiration = Duration.ofDays(30);
        private String secret = "defaultSecretChangeThis";
    }
    
    @Data
    public static class RateLimit {
        private int maxLoginAttempts = 5;
        private Duration lockoutDuration = Duration.ofMinutes(15);
        private int requestsPerMinute = 60;
    }
    
    @Data
    public static class Session {
        private Duration sessionTimeout = Duration.ofHours(24);
        private boolean rememberMe = true;
        private Duration rememberMeDuration = Duration.ofDays(30);
    }
}
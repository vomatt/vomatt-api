package com.vomattapi.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Simplified Thymeleaf configuration - only loaded when email is enabled
 * All email template functionality has been removed when email service is disabled
 */
@Configuration
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
public class ThymeleafConfig {
    // Configuration is empty when email is disabled
}
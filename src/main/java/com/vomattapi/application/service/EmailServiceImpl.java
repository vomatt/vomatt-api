package com.vomattapi.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Simplified implementation of EmailService that doesn't require email libraries
 */
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true", matchIfMissing = false)
public class EmailServiceImpl implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailServiceImpl.class);

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    /**
     * Send email verification code (disabled)
     */
    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        logger.info("Email service is disabled. Verification email not sent to: {}", to);
    }

    /**
     * Send password reset email with reset token (disabled)
     */
    @Override
    public void sendPasswordResetEmail(String to, String resetToken) {
        logger.info("Email service is disabled. Password reset email not sent to: {}", to);
    }

    /**
     * Send welcome email to new member (disabled)
     */
    @Override
    public void sendWelcomeEmail(String to, String username) {
        logger.info("Email service is disabled. Welcome email not sent to: {}", to);
    }
}
package com.vomattapi.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * No-operation implementation of EmailService used when email functionality is disabled
 */
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "false")
public class EmailServiceNoOp implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailServiceNoOp.class);

    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        logger.debug("Email service disabled: Skipping pre-signup email to {}", to);
    }

    @Override
    public void sendPreSignupEmail(String to, String verificationCode, Locale locale) {
        logger.debug("Email service disabled: Skipping pre-signup email to {} with locale {}", to, locale);
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        logger.debug("Email service disabled: Skipping verification email to {}", to);
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode, Locale locale) {
        logger.debug("Email service disabled: Skipping verification email to {} with locale {}", to, locale);
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetToken) {
        logger.debug("Email service disabled: Skipping password reset email to {}", to);
    }

    @Override
    public void sendWelcomeEmail(String to, String username) {
        logger.debug("Email service disabled: Skipping welcome email to {}", to);
    }

    @Override
    public void sendWelcomeEmail(String to, String username, Locale locale) {
        logger.debug("Email service disabled: Skipping welcome email to {} with locale {}", to, locale);
    }
}
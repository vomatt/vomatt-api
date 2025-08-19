package com.vomattapi.application.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.vomattapi.application.service.EmailService;

/**
 * No-op implementation of EmailService in the impl package
 */
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "false")
public class EmailServiceImpl implements EmailService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailServiceImpl.class);

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        logger.debug("Email service disabled: Not sending verification email to {}", to);
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetToken) {
        logger.debug("Email service disabled: Not sending password reset email to {}", to);
    }

    @Override
    public void sendWelcomeEmail(String to, String username) {
        logger.debug("Email service disabled: Not sending welcome email to {}", to);
    }
}

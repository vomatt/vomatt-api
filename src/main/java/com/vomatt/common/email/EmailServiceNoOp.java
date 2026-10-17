package com.vomatt.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * No-operation implementation of EmailService used when email functionality is disabled
 */
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "false", matchIfMissing = true)
public class EmailServiceNoOp implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailServiceNoOp.class);

    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        logger.debug("Email service disabled: Skipping pre-signup email to {}", to);
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        logger.debug("Email service disabled: Skipping verification email to {}", to);
    }

    @Override
    public void sendWelcomeEmail(String to, String username, Locale locale) {
        logger.debug("Email service disabled: Skipping welcome email to {}", to);
    }

    @Override
    public void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        logger.debug("Email service disabled: Skipping {} invitation email(s)", recipients.size());
    }
}

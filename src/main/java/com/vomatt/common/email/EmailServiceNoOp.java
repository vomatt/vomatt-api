package com.vomatt.common.email;

import com.vomatt.common.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * EmailService used when email is disabled: logs the OTP instead of sending it, which is how local development reads
 * it (Redis holds only a hash). Never loaded under the prod profile, where a missing real sender must fail startup
 * rather than print codes into the logs.
 */
@Service
@Profile("!prod")
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "false", matchIfMissing = true)
public class EmailServiceNoOp implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailServiceNoOp.class);

    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        logger.info("[EMAIL-NOOP] pre-signup OTP {} for {}", verificationCode, TextUtil.maskEmail(to));
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        logger.info("[EMAIL-NOOP] login OTP {} for {}", verificationCode, TextUtil.maskEmail(to));
    }

    @Override
    public void sendAdminLoginEmail(String to, String verificationCode) {
        logger.info("[EMAIL-NOOP] admin OTP {} for {}", verificationCode, TextUtil.maskEmail(to));
    }

    @Override
    public void sendWelcomeEmail(String to, String username, Locale locale) {
        logger.debug("Email service disabled: skipping welcome email to {}", TextUtil.maskEmail(to));
    }

    @Override
    public void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        logger.debug("Email service disabled: skipping {} invitation email(s)", recipients.size());
    }
}

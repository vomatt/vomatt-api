package com.vomatt.common.email;

import com.vomatt.common.constant.OtpLogProfiles;
import com.vomatt.common.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * EmailService used when email is disabled: logs the OTP instead of sending it, which is how local development reads
 * it (Redis holds only a hash). Loaded only under the dev or loadtest profile (never with prod); under any other or
 * missing profile, email disabled leaves no EmailService and startup fails rather than print codes into the logs.
 */
@Service
@Profile(OtpLogProfiles.ALLOWED)
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
    public void sendWelcomeEmail(String to, Locale locale) {
        logger.debug("Email service disabled: skipping welcome email to {}", TextUtil.maskEmail(to));
    }

    @Override
    public void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        logger.debug("Email service disabled: skipping {} invitation email(s)", recipients.size());
    }

    @Override
    public void sendSignupInvitationEmails(List<String> recipients, String inviterName, Locale locale) {
        logger.info("[EMAIL-NOOP] {} sign-up invitation(s) from {}", recipients.size(), inviterName);
    }

    @Override
    public void sendCommunityInvitationEmail(String to, UUID invitationId, String inviterName, String inviterUsername,
                                             String communityName, String role, Duration expiresIn, Locale locale) {
        logger.info("[EMAIL-NOOP] invitation {} to community {} as {} for {}", invitationId, communityName, role,
                TextUtil.maskEmail(to));
    }
}

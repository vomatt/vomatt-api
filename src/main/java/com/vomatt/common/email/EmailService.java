package com.vomatt.common.email;

import java.util.Locale;
import java.util.UUID;

/**
 * Sends transactional emails; {@link EmailServiceNoOp} stands in when {@code app.email.enabled=false}.
 */
public interface EmailService {

    /** Sends the OTP for a new address (sign-up). Throws when delivery fails. */
    void sendPreSignupEmail(String to, String verificationCode);

    /** Sends the OTP for a registered address (login). Throws when delivery fails. */
    void sendVerificationEmail(String to, String verificationCode);

    /**
     * Sends the welcome email in the background; failures are only logged.
     *
     * @param locale captured by the caller, because the background thread has no request locale
     */
    void sendWelcomeEmail(String to, String username, Locale locale);

    /**
     * Sends an Invitation to an email Invitee in the background, linking to the Poll; failures are only logged.
     *
     * @param locale captured from the inviting owner's request, because the background thread has no request locale
     */
    void sendInvitationEmail(String to, String inviterName, String pollTitle, UUID pollId, Locale locale);
}

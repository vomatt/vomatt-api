package com.vomatt.common.email;

import java.util.List;
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
     * Sends the Poll's Invitation to each email Invitee, one after another in a single background task, linking to the
     * Poll; failures are only logged and do not stop the rest. Callers record the addresses as sent before this runs,
     * so delivery is at most once: a failed send, or a crash partway through the list, is never retried.
     *
     * @param locale captured from the inviting owner's request, because the background thread has no request locale
     */
    void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale);
}

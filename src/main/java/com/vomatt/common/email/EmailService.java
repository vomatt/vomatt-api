package com.vomatt.common.email;

import java.time.Duration;
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

    /** Sends the OTP for the admin console login, worded and titled apart from the consumer login. Throws when delivery fails. */
    void sendAdminLoginEmail(String to, String verificationCode);

    /**
     * Sends the welcome email in the background; failures are only logged.
     *
     * @param locale captured by the caller, because the background thread has no request locale
     */
    void sendWelcomeEmail(String to, Locale locale);

    /**
     * Sends the Poll's Invitation to each email Invitee, one after another in a single background task, linking to the
     * Poll; failures are only logged and do not stop the rest. Callers record the addresses as sent before this runs,
     * so delivery is at most once: a failed send, or a crash partway through the list, is never retried.
     *
     * @param locale captured from the inviting owner's request, because the background thread has no request locale
     */
    void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale);

    /**
     * Invites each address, which has no account, to sign up, on behalf of an Invite List owner; one background task
     * mails them one after another, failures are only logged. Callers record the addresses as sent before this runs.
     */
    void sendSignupInvitationEmails(List<String> recipients, String inviterName, Locale locale);

    /**
     * Invites an address with no account to join a community account in the background, failures only logged: once
     * signed up with the address, the invitee accepts it like any other ({@code role} is an enum name, labelled from
     * {@code community.role.*}). The button opens the invitation's page.
     */
    void sendCommunityInvitationEmail(String to, UUID invitationId, String inviterName, String inviterUsername,
                                      String communityName, String role, Duration expiresIn, Locale locale);
}

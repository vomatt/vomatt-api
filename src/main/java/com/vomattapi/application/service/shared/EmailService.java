package com.vomattapi.application.service.shared;

import java.util.Locale;

/**
 * EmailService interface with i18n support
 */
public interface EmailService {

    /**
     * Send pre-signup email verification code
     */
    void sendPreSignupEmail(String to, String verificationCode);

    /**
     * Send login verification email
     */
    void sendVerificationEmail(String to, String verificationCode);

    /**
     * Send pre-signup email verification code with locale support
     */
    default void sendPreSignupEmail(String to, String verificationCode, Locale locale) {
        sendPreSignupEmail(to, verificationCode);
    }

    /**
     * Send login verification email with locale support
     */
    default void sendVerificationEmail(String to, String verificationCode, Locale locale) {
        sendVerificationEmail(to, verificationCode);
    }

    /**
     * Send password reset email with reset token
     */
    void sendPasswordResetEmail(String to, String resetToken);

    /**
     * Send welcome email to new user
     */
    void sendWelcomeEmail(String to, String username);

    /**
     * Send welcome email with locale support
     */
    default void sendWelcomeEmail(String to, String username, Locale locale) {
        sendWelcomeEmail(to, username);
    }
}
package com.vomattapi.application.service;

/**
 * Simplified EmailService interface with all email functionality disabled
 */
public interface EmailService {
    
    /**
     * Send verification email (no-op implementation)
     */
    void sendVerificationEmail(String to, String verificationCode);
    
    /**
     * Send password reset email (no-op implementation)
     */
    void sendPasswordResetEmail(String to, String resetToken);
    
    /**
     * Send welcome email (no-op implementation)
     */
    void sendWelcomeEmail(String to, String username);
}
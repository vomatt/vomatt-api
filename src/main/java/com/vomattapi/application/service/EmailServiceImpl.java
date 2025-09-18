package com.vomattapi.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import lombok.extern.slf4j.Slf4j;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Locale;

/**
 * Gmail implementation of EmailService
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;
    private final LocaleService localeService;

    @Value("${app.email.from:noreply@vomatt.com}")
    private String fromEmail;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    /**
     * Send pre-signup email verification code using HTML template with auto-detected locale from request
     */
    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        Locale requestLocale = localeService.getCurrentRequestLocale();
        sendVerificationEmail(to, verificationCode, true, requestLocale);
    }

    /**
     * Send pre-signup email verification code with custom locale
     */
    @Override
    public void sendPreSignupEmail(String to, String verificationCode, Locale locale) {
        sendVerificationEmail(to, verificationCode, true, locale);
    }

    /**
     * Send email verification code using HTML template with auto-detected locale from request
     */
    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        Locale requestLocale = localeService.getCurrentRequestLocale();
        sendVerificationEmail(to, verificationCode, false, requestLocale);
    }

    /**
     * Send verification email with custom locale
     */
    @Override
    public void sendVerificationEmail(String to, String verificationCode, Locale locale) {
        sendVerificationEmail(to, verificationCode, false, locale);
    }

    /**
     * Private method to send verification email with i18n support
     */
    private void sendVerificationEmail(String to, String verificationCode, boolean isPreSignup, Locale locale) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);

            // Get localized subject
            String subject = messageSource.getMessage("email.verification.subject", null, locale);
            helper.setSubject(subject);

            // Create Thymeleaf context with locale
            Context context = new Context(locale);
            context.setVariable("verificationCode", verificationCode);
            context.setVariable("isPreSignup", isPreSignup);

            // Choose appropriate template
            String templateName = isPreSignup ? "email/pre-signup-email-verification" : "email/email-verification";
            String htmlContent = templateEngine.process(templateName, context);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("Verification email ({}) sent successfully to: {}", isPreSignup ? "pre-signup" : "login", to);

        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send verification email to: {}", to, e);
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    /**
     * Send password reset email with reset token
     */
    @Override
    public void sendPasswordResetEmail(String to, String resetToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject("Vomatt - Password Reset");
//            message.setText(buildPasswordResetEmailBody(resetToken));
            
            mailSender.send(message);
            log.info("Password reset email sent successfully to: {}", to);
            
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", to, e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    /**
     * Send welcome email to new member using HTML template with i18n support
     */
    @Override
    public void sendWelcomeEmail(String to, String username) {
        Locale requestLocale = localeService.getCurrentRequestLocale();
        sendWelcomeEmail(to, username, requestLocale);
    }

    /**
     * Send welcome email with custom locale
     */
    public void sendWelcomeEmail(String to, String username, Locale locale) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);

            // Get localized subject
            String subject = messageSource.getMessage("email.welcome.subject", null, locale);
            helper.setSubject(subject);

            // Create Thymeleaf context with locale
            Context context = new Context(locale);
            context.setVariable("username", username);

            // Process welcome email template
            String htmlContent = templateEngine.process("email/welcome-email", context);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("Welcome email sent successfully to: {} with locale: {}", to, locale);

        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send welcome email to: {}", to, e);
            throw new RuntimeException("Failed to send welcome email", e);
        }
    }

}
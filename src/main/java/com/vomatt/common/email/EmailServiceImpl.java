package com.vomatt.common.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Year;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SMTP implementation of EmailService (HTML templates under {@code templates/email}, text from {@code messages*.properties})
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    @Value("${app.email.from:noreply@vomatt.com}")
    private String fromEmail;

    @Value("${otp.ttl-seconds:180}")
    private long otpTtlSeconds;

    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        sendVerificationEmail(to, verificationCode, true);
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        sendVerificationEmail(to, verificationCode, false);
    }

    private void sendVerificationEmail(String to, String verificationCode, boolean isPreSignup) {
        try {
            send(to, "email.verification.subject", "email/email-verification", LocaleContextHolder.getLocale(),
                    Map.of("verificationCode", verificationCode,
                            "isPreSignup", isPreSignup,
                            // the email states the real OTP lifetime, rounded up to whole minutes
                            "expiryMinutes", (otpTtlSeconds + 59) / 60));
            log.info("Verification email ({}) sent to: {}", isPreSignup ? "pre-signup" : "login", to);
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send verification email to: {}", to, e);
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    @Async
    @Override
    public void sendWelcomeEmail(String to, String username, Locale locale) {
        try {
            send(to, "email.welcome.subject", "email/welcome-email", locale, Map.of("username", Objects.toString(username, "")));
            log.info("Welcome email sent to: {} with locale: {}", to, locale);
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send welcome email to: {}", to, e);
        }
    }

    private void send(String to, String subjectKey, String template, Locale locale, Map<String, Object> variables)
            throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(messageSource.getMessage(subjectKey, null, locale));

        Context context = new Context(locale, variables);
        // a String, so MessageFormat does not render it as "2,026"
        context.setVariable("year", String.valueOf(Year.now().getValue()));
        helper.setText(templateEngine.process(template, context), true);

        mailSender.send(mimeMessage);
    }
}

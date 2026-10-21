package com.vomatt.common.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * EmailService over SMTP or the Resend HTTP API, chosen by {@code app.email.provider} (HTML templates under
 * {@code templates/email}, text from {@code messages*.properties})
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private static final ClassPathResource LOGO = new ClassPathResource("templates/email/logo.png");
    // Same budget as the spring.mail SMTP timeouts: OTP emails are sent on the request thread
    private static final Duration RESEND_TIMEOUT = Duration.ofSeconds(5);
    // Resend allows 2 requests per second per team by default
    private static final Duration RESEND_SEND_INTERVAL = Duration.ofMillis(500);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    @Value("${app.email.from:noreply@vomatt.com}")
    private String fromEmail;

    @Value("${app.email.site-url:https://vomatt.com}")
    private String siteUrl;

    @Value("${app.email.poll-path:/poll/{voteId}}")
    private String pollPath;

    @Value("${otp.ttl-seconds:180}")
    private long otpTtlSeconds;

    @Value("${app.email.provider:smtp}")
    private String provider;

    @Value("${app.email.resend-api-key:}")
    private String resendApiKey;

    // null means SMTP
    private Resend resend;
    private String logoBase64;

    @PostConstruct
    void initProvider() {
        switch (provider) {
            case "smtp" -> { }
            case "resend" -> {
                // Fail at startup rather than on the first OTP request
                String apiKey = resendApiKey.strip();
                if (apiKey.isEmpty()) {
                    throw new IllegalStateException("app.email.provider=resend requires app.email.resend-api-key");
                }
                resend = Resend.builder().apiKey(apiKey)
                        .connectTimeout(RESEND_TIMEOUT).readTimeout(RESEND_TIMEOUT).writeTimeout(RESEND_TIMEOUT)
                        .build();
                logoBase64 = readLogoBase64();
            }
            default -> throw new IllegalStateException("Unknown app.email.provider: " + provider + " (smtp or resend)");
        }
    }

    @Override
    public void sendPreSignupEmail(String to, String verificationCode) {
        sendVerificationEmail(to, verificationCode, "email.verification.subject", "email.verification.pre.signup.message", "pre-signup");
    }

    @Override
    public void sendVerificationEmail(String to, String verificationCode) {
        sendVerificationEmail(to, verificationCode, "email.verification.subject", "email.verification.login.message", "login");
    }

    @Override
    public void sendAdminLoginEmail(String to, String verificationCode) {
        sendVerificationEmail(to, verificationCode, "email.verification.admin.subject", "email.verification.admin.message", "admin");
    }

    private void sendVerificationEmail(String to, String verificationCode, String subjectKey, String messageKey, String kind) {
        try {
            Locale locale = LocaleContextHolder.getLocale();
            send(to, subjectKey, "email/email-verification", locale,
                    Map.of("verificationCode", verificationCode,
                            "verificationMessage", messageSource.getMessage(messageKey, null, locale),
                            // the email states the real OTP lifetime, rounded up to whole minutes
                            "expiryMinutes", (otpTtlSeconds + 59) / 60));
            log.info("Verification email ({}) sent to: {}", kind, to);
        } catch (MessagingException | ResendException | RuntimeException e) {
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
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send welcome email to: {}", to, e);
        }
    }

    // Serial on purpose: one task per batch keeps a large invite from opening many SMTP sessions at once, and over
    // Resend it is paced under the per-second rate limit, which would otherwise reject part of the list with 429
    // ponytail: the pace ignores OTP emails sharing the same quota; retry on 429 if invites and logins collide
    @Async
    @Override
    public void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        for (int i = 0; i < recipients.size(); i++) {
            if (i > 0 && resend != null && !pause(RESEND_SEND_INTERVAL)) {
                log.warn("Invitation emails for vote {} interrupted, {} not sent", pollId, recipients.size() - i);
                return;
            }
            sendInvitationEmail(recipients.get(i), inviterName, pollTitle, pollId, locale);
        }
    }

    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sendInvitationEmail(String to, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        try {
            send(to, "email.invitation.subject", "email/invitation-email", locale,
                    Map.of("inviterName", Objects.toString(inviterName, ""),
                            "pollTitle", pollTitle,
                            "pollUrl", siteUrl + pollPath.replace("{voteId}", pollId.toString())));
            log.info("Invitation email for vote {} sent to: {}", pollId, mask(to));
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send invitation email for vote {} to: {}", pollId, mask(to), e);
        }
    }

    // The invitee has no account yet, so their address stays out of the logs: a***@domain
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private void send(String to, String subjectKey, String template, Locale locale, Map<String, Object> variables)
            throws MessagingException, ResendException {
        String subject = messageSource.getMessage(subjectKey, null, locale);
        Context context = new Context(locale, variables);
        context.setVariable("siteUrl", siteUrl);
        String html = templateEngine.process(template, context);

        if (resend != null) {
            sendViaResend(to, subject, html);
        } else {
            sendViaSmtp(to, subject, html);
        }
    }

    private void sendViaSmtp(String to, String subject, String html) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "utf-8");
        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        // Inline (CID) image: Gmail and Outlook strip inline SVG and block data: URIs
        helper.addInline("logo", LOGO, "image/png");

        mailSender.send(mimeMessage);
    }

    private void sendViaResend(String to, String subject, String html) throws ResendException {
        resend.emails().send(CreateEmailOptions.builder()
                .from(fromEmail)
                .to(to)
                .subject(subject)
                .html(html)
                // contentId makes it an inline image, so cid:logo in the templates resolves as it does over SMTP
                .attachments(Attachment.builder()
                        .fileName("logo.png")
                        .contentType("image/png")
                        .content(logoBase64)
                        .contentId("logo")
                        .build())
                .build());
    }

    private static String readLogoBase64() {
        try {
            return Base64.getEncoder().encodeToString(LOGO.getContentAsByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

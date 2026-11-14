package com.vomatt.common.email;

import com.resend.Resend;
import com.vomatt.common.util.TextUtil;
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
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    @Value("${app.email.from:Vomatt <noreply@vomatt.com>}")
    private String fromEmail;

    // Blank means no Reply-To; set it only to a mailbox that receives mail
    @Value("${app.email.reply-to:}")
    private String replyTo;

    @Value("${app.email.site-url:https://vomatt.com}")
    private String siteUrl;

    // The footer's legal and contact pages live on the about site, in the email's language
    @Value("${app.email.about-url:https://about.vomatt.com}")
    private String aboutUrl;

    @Value("${app.email.poll-path:/poll/{voteId}}")
    private String pollPath;

    // Where a sign-up invitation's button leads: the sign-up page, as signing up with the address is what joins
    @Value("${app.email.signup-path:/login}")
    private String signupPath;

    @Value("${app.email.community-invitation-path:/notifications/community-invitations/{invitationId}}")
    private String communityInvitationPath;

    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
            log.info("Verification email ({}) sent to: {}", kind, TextUtil.maskEmail(to));
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send verification email to: {}", TextUtil.maskEmail(to), e);
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    @Async
    @Override
    public void sendWelcomeEmail(String to, Locale locale) {
        try {
            send(to, "email.welcome.subject", "email/welcome-email", locale, Map.of());
            log.info("Welcome email sent to: {} with locale: {}", TextUtil.maskEmail(to), locale);
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send welcome email to: {}", TextUtil.maskEmail(to), e);
        }
    }

    // Serial on purpose: one task per batch keeps a large invite from opening many SMTP sessions at once, and over
    // Resend it is paced under the per-second rate limit, which would otherwise reject part of the list with 429
    // ponytail: the pace ignores OTP emails sharing the same quota; retry on 429 if invites and logins collide
    @Async
    @Override
    public void sendInvitationEmails(List<String> recipients, String inviterName, String pollTitle, UUID pollId, Locale locale) {
        sendPaced("vote " + pollId, recipients, to -> sendInvitationEmail(to, inviterName, pollTitle, pollId, locale));
    }

    // One after another; over Resend, paced under its per-second rate limit
    private void sendPaced(String batch, List<String> recipients, Consumer<String> send) {
        for (int i = 0; i < recipients.size(); i++) {
            if (i > 0 && resend != null && !pause(RESEND_SEND_INTERVAL)) {
                log.warn("Invitation emails for {} interrupted, {} not sent", batch, recipients.size() - i);
                return;
            }
            send.accept(recipients.get(i));
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
            log.info("Invitation email for vote {} sent to: {}", pollId, TextUtil.maskEmail(to));
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send invitation email for vote {} to: {}", pollId, TextUtil.maskEmail(to), e);
        }
    }

    @Async
    @Override
    public void sendSignupInvitationEmails(List<String> recipients, String inviterName, Locale locale) {
        sendPaced("sign-up invitations", recipients, to -> sendJoinInvitation(to, "vomatt", siteUrl + signupPath, locale,
                Map.of("inviterName", Objects.toString(inviterName, ""))));
    }

    @Async
    @Override
    public void sendCommunityInvitationEmail(String to, UUID invitationId, String inviterName, String inviterUsername,
                                             String communityName, String role, Duration expiresIn, Locale locale) {
        sendJoinInvitation(to, "community", siteUrl + communityInvitationPath.replace("{invitationId}", invitationId.toString()), locale,
                Map.of("inviterName", Objects.toString(inviterName, ""), "inviterUsername", inviterUsername,
                        "communityName", communityName, "role", role, "expiresDays", expiresIn.toDays()));
    }

    // Shaped like a team invitation: greeting by address, the invite, a button and its URL, then when and to whom it was
    // sent. kind picks the texts, email.join.<kind>.*
    private void sendJoinInvitation(String to, String kind, String joinUrl, Locale locale, Map<String, Object> variables) {
        String subjectKey = "email.join." + kind + ".subject";
        Map<String, Object> all = new HashMap<>(variables);
        all.put("kind", kind);
        all.put("email", to);
        all.put("joinUrl", joinUrl);
        all.put("sentAt", OffsetDateTime.now(ZoneOffset.UTC).format(SENT_AT));
        try {
            send(to, subjectKey, "email/join-invitation-email", locale, all);
            log.info("Join invitation email ({}) sent to: {}", subjectKey, TextUtil.maskEmail(to));
        } catch (MessagingException | ResendException | RuntimeException e) {
            log.error("Failed to send join invitation email ({}) to: {}", subjectKey, TextUtil.maskEmail(to), e);
        }
    }

    private void send(String to, String subjectKey, String template, Locale locale, Map<String, Object> variables)
            throws MessagingException, ResendException {
        String subject = messageSource.getMessage(subjectKey, null, locale);
        Context context = new Context(locale, variables);
        context.setVariable("siteUrl", siteUrl);
        context.setVariable("aboutUrl", aboutUrl + ("zh".equals(locale.getLanguage()) ? "/zh" : ""));
        String html = templateEngine.process(template, context);
        // An HTML-only email scores as spam with Gmail and others, so every email carries a text/plain part too
        String text = toPlainText(html);

        if (resend != null) {
            sendViaResend(to, subject, text, html);
        } else {
            sendViaSmtp(to, subject, text, html);
        }
    }

    private void sendViaSmtp(String to, String subject, String text, String html) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "utf-8");
        helper.setFrom(fromEmail);
        if (!replyTo.isBlank()) {
            helper.setReplyTo(replyTo);
        }
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, html);
        // Inline (CID) image: Gmail and Outlook strip inline SVG and block data: URIs
        helper.addInline("logo", LOGO, "image/png");

        mailSender.send(mimeMessage);
    }

    private void sendViaResend(String to, String subject, String text, String html) throws ResendException {
        CreateEmailOptions.Builder email = CreateEmailOptions.builder()
                .from(fromEmail)
                .to(to)
                .subject(subject)
                .text(text)
                .html(html)
                // contentId makes it an inline image, so cid:logo in the templates resolves as it does over SMTP
                .attachments(Attachment.builder()
                        .fileName("logo.png")
                        .contentType("image/png")
                        .content(logoBase64)
                        .contentId("logo")
                        .build());
        if (!replyTo.isBlank()) {
            email.replyTo(replyTo);
        }
        resend.emails().send(email.build());
    }

    /**
     * Text version of a rendered template: links keep their URL, images their alt text, block ends become line breaks.
     * Enough for the table layout of templates/email; it is not a general HTML-to-text converter.
     */
    static String toPlainText(String html) {
        String text = html
                .replaceAll("(?is)<head>.*?</head>|<!--.*?-->", "")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)<img\\s[^>]*?alt=\"([^\"]*)\"[^>]*>", "$1")
                .replaceAll("(?i)<a\\s[^>]*?href=\"([^\"]*)\"[^>]*>(.*?)</a>", "$2 ($1)")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|h1|tr|table)>", "\n")
                .replaceAll("<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).lines().map(String::strip).filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static String readLogoBase64() {
        try {
            return Base64.getEncoder().encodeToString(LOGO.getContentAsByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

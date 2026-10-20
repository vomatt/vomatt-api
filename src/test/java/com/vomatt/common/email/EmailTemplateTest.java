package com.vomatt.common.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import com.vomatt.common.config.I18nConfig;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Renders the email templates through {@link EmailServiceImpl} with the app's real {@code I18nConfig} message source,
 * and checks the sent HTML. The test profile runs with email disabled, so nothing else renders these templates.
 */
class EmailTemplateTest {

    private static final Locale ZH_TW = Locale.forLanguageTag("zh-TW");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailServiceImpl emailService = createEmailService();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void shouldRenderPreSignupEmailWhenLocaleIsZhTw() throws Exception {
        LocaleContextHolder.setLocale(ZH_TW);
        emailService.sendPreSignupEmail("a@example.com", "123456");

        String html = sentHtml();
        assertThat(html).contains("123456", "感謝您註冊", "3 分鐘", "服務條款", "https://staging.vomatt.com/privacy-policy")
                .doesNotContain("??", "©", "點一下驗證碼");
    }

    @Test
    void shouldRenderLoginEmailWhenLocaleIsEn() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        emailService.sendVerificationEmail("a@example.com", "654321");

        String html = sentHtml();
        assertThat(html).contains("654321", "complete your login", "expire in 3 minutes",
                "Contact Us", "https://staging.vomatt.com/terms-of-service").doesNotContain("??", "pre-signup", "©", "Tap the code");
    }

    @Test
    void shouldRenderWelcomeEmailWhenLocaleIsZhTwOrEn() throws Exception {
        emailService.sendWelcomeEmail("a@example.com", "Amy", ZH_TW);
        assertThat(sentHtml()).contains("您好 Amy", "投下您的第一票", "隱私權政策", "lang=\"zh-TW\"").doesNotContain("??");

        emailService.sendWelcomeEmail("b@example.com", "Bob", Locale.ENGLISH);
        assertThat(sentHtml()).contains("Hello Bob", "cast your first Ballot", "Privacy Policy", "lang=\"en\"").doesNotContain("??");
    }

    @Test
    void shouldRenderInvitationEmailWithPollLinkWhenLocaleIsZhTwOrEn() throws Exception {
        UUID pollId = UUID.fromString("0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05");
        emailService.sendInvitationEmails(List.of("a@example.com"), "Amy", "Lunch <b>vote</b>", pollId, ZH_TW);
        assertThat(sentHtml()).contains("Amy 邀請您", "Lunch &lt;b&gt;vote&lt;/b&gt;",
                "https://staging.vomatt.com/poll/" + pollId, "前往投票").doesNotContain("??", "<b>vote", "謝謝");

        emailService.sendInvitationEmails(List.of("b@example.com"), "Amy", "Lunch", pollId, Locale.ENGLISH);
        assertThat(sentHtml()).contains("Amy invited you", "Open the Poll", "https://staging.vomatt.com/poll/" + pollId)
                .doesNotContain("??", "Thank you");
    }

    @Test
    void shouldSendOverResendWithInlineLogoWhenProviderIsResend() throws Exception {
        Emails emails = useResend();

        emailService.sendWelcomeEmail("a@example.com", "Amy", Locale.ENGLISH);

        ArgumentCaptor<CreateEmailOptions> captor = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(captor.capture());
        CreateEmailOptions sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("noreply@vomatt.com");
        assertThat(sent.getTo()).containsExactly("a@example.com");
        assertThat(sent.getHtml()).contains("Hello Amy", "cid:logo");
        Attachment logo = sent.getAttachments().getFirst();
        assertThat(logo.getContentId()).isEqualTo("logo");
        assertThat(logo.getContent()).isNotBlank();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldSendEnglishResendTemplateWithEscapedVariablesWhenResendTemplatesEnabled() throws Exception {
        Emails emails = useResend();
        ReflectionTestUtils.setField(emailService, "resendTemplates", true);
        UUID pollId = UUID.fromString("0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05");

        emailService.sendInvitationEmails(List.of("a@example.com"), "Amy", "Lunch <b>vote</b>", pollId, Locale.ENGLISH);

        ArgumentCaptor<CreateEmailOptions> captor = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(captor.capture());
        CreateEmailOptions sent = captor.getValue();
        assertThat(sent.getHtml()).isNull();
        assertThat(sent.getSubject()).isEqualTo("You are invited to a Vomatt Poll");
        assertThat(sent.getTemplate().getId()).isEqualTo("invitation-email-en");
        // Keys must match the {{{VAR}}} names in the Resend Template, which rejects a send missing any of them
        assertThat(sent.getTemplate().getVariables()).containsOnly(
                entry("INVITER_NAME", "Amy"),
                entry("POLL_TITLE", "Lunch &lt;b&gt;vote&lt;/b&gt;"),
                entry("POLL_URL", "https://staging.vomatt.com/poll/" + pollId),
                entry("SITE_URL", "https://staging.vomatt.com"));
    }

    @Test
    void shouldFallBackToZhTwResendTemplateWhenLocaleIsUnsupported() throws Exception {
        Emails emails = useResend();
        ReflectionTestUtils.setField(emailService, "resendTemplates", true);
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        emailService.sendVerificationEmail("a@example.com", "654321");

        ArgumentCaptor<CreateEmailOptions> captor = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(captor.capture());
        assertThat(captor.getValue().getTemplate().getId()).isEqualTo("email-verification-zh-tw");
        assertThat(captor.getValue().getTemplate().getVariables()).containsOnly(
                entry("VERIFICATION_CODE", "654321"),
                entry("VERIFICATION_MESSAGE", "請使用以下驗證碼完成登入："),
                entry("EXPIRY_MINUTES", "3"),
                entry("SITE_URL", "https://staging.vomatt.com"));
    }

    @Test
    void shouldFailStartupWhenResendTemplatesEnabledOverSmtp() {
        ReflectionTestUtils.setField(emailService, "provider", "smtp");
        ReflectionTestUtils.setField(emailService, "resendTemplates", true);
        assertThatThrownBy(emailService::initProvider).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenResendRejectsVerificationEmail() throws Exception {
        Emails emails = useResend();
        when(emails.send(any(CreateEmailOptions.class))).thenThrow(new ResendException(429, "rate_limit_exceeded"));

        // OTP callers rely on the throw to report the failure instead of claiming the code was sent
        assertThatThrownBy(() -> emailService.sendVerificationEmail("a@example.com", "123456"))
                .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(ResendException.class);
    }

    @Test
    void shouldInitResendWhenApiKeyHasSurroundingWhitespace() {
        ReflectionTestUtils.setField(emailService, "provider", "resend");
        ReflectionTestUtils.setField(emailService, "resendApiKey", " re_test\n");
        emailService.initProvider();

        assertThat(ReflectionTestUtils.getField(emailService, "resend")).isNotNull();
        assertThat((String) ReflectionTestUtils.getField(emailService, "logoBase64")).isNotBlank();
    }

    @Test
    void shouldFailStartupWhenResendHasNoApiKey() {
        ReflectionTestUtils.setField(emailService, "provider", "resend");
        ReflectionTestUtils.setField(emailService, "resendApiKey", " ");
        assertThatThrownBy(emailService::initProvider).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailStartupWhenProviderIsUnknown() {
        ReflectionTestUtils.setField(emailService, "provider", "sendgrid");
        assertThatThrownBy(emailService::initProvider).isInstanceOf(IllegalStateException.class);
    }

    private Emails useResend() {
        Resend resend = mock(Resend.class);
        Emails emails = mock(Emails.class);
        when(resend.emails()).thenReturn(emails);
        ReflectionTestUtils.setField(emailService, "resend", resend);
        ReflectionTestUtils.setField(emailService, "logoBase64", "bG9nbw==");
        return emails;
    }

    @Test
    void shouldMaskAddressWhenLoggingInvitation() {
        assertThat(EmailServiceImpl.mask("carol@example.com")).isEqualTo("c***@example.com");
        assertThat(EmailServiceImpl.mask("broken")).isEqualTo("***");
    }

    @Test
    void shouldPointToFrontendPollRouteWhenPollPathDefaultResolved() {
        // The braces of {voteId} must survive placeholder resolution
        assertThat(defaultPollPath()).isEqualTo("/poll/{voteId}");
    }

    /** {@code app.email.poll-path} from application.yml, resolved as Spring would with EMAIL_POLL_PATH unset. */
    private static String defaultPollPath() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String placeholder = yaml.getObject().getProperty("app.email.poll-path");
        return new StandardEnvironment().resolvePlaceholders(placeholder.replace("EMAIL_POLL_PATH", "EMAIL_POLL_PATH_UNSET"));
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        assertThat(findPart(message, "image/png")).as("inline logo").isNotNull();
        String html = (String) findPart(message, "text/html").getContent();
        assertThat(html).contains("cid:logo");
        return html;
    }

    private Part findPart(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) {
            return part;
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                Part found = findPart(multipart.getBodyPart(i), mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private EmailServiceImpl createEmailService() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new JavaMailSenderImpl().createMimeMessage());
        MessageSource messageSource = new I18nConfig().messageSource();

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");
        templateResolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(messageSource);
        engine.setTemplateResolver(templateResolver);

        EmailServiceImpl service = new EmailServiceImpl(mailSender, engine, messageSource);
        ReflectionTestUtils.setField(service, "fromEmail", "noreply@vomatt.com");
        ReflectionTestUtils.setField(service, "siteUrl", "https://staging.vomatt.com");
        ReflectionTestUtils.setField(service, "otpTtlSeconds", 180L);
        ReflectionTestUtils.setField(service, "pollPath", defaultPollPath());
        return service;
    }
}

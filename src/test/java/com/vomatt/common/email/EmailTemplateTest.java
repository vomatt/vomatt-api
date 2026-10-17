package com.vomatt.common.email;

import com.vomatt.common.config.I18nConfig;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(html).contains("123456", "感謝您註冊", "3 分鐘", "點一下驗證碼", "服務條款", "https://staging.vomatt.com/privacy-policy")
                .doesNotContain("??", "©");
    }

    @Test
    void shouldRenderLoginEmailWhenLocaleIsEn() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        emailService.sendVerificationEmail("a@example.com", "654321");

        String html = sentHtml();
        assertThat(html).contains("654321", "complete your login", "expire in 3 minutes",
                "Tap the code", "Contact Us", "https://staging.vomatt.com/terms-of-service").doesNotContain("??", "pre-signup", "©");
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
                "https://staging.vomatt.com/votes/" + pollId, "前往投票").doesNotContain("??", "<b>vote");

        emailService.sendInvitationEmails(List.of("b@example.com"), "Amy", "Lunch", pollId, Locale.ENGLISH);
        assertThat(sentHtml()).contains("Amy invited you", "Open the Poll", "https://staging.vomatt.com/votes/" + pollId)
                .doesNotContain("??");
    }

    @Test
    void shouldMaskAddressWhenLoggingInvitation() {
        assertThat(EmailServiceImpl.mask("carol@example.com")).isEqualTo("c***@example.com");
        assertThat(EmailServiceImpl.mask("broken")).isEqualTo("***");
    }

    @Test
    void shouldKeepVoteIdTokenWhenPollPathDefaultResolved() {
        // Same placeholder as application.yml / EmailServiceImpl: the braces of {voteId} must survive resolution
        assertThat(new StandardEnvironment().resolvePlaceholders("${EMAIL_POLL_PATH_UNSET:/votes/{voteId}}"))
                .isEqualTo("/votes/{voteId}");
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
        ReflectionTestUtils.setField(service, "pollPath", "/votes/{voteId}");
        return service;
    }
}

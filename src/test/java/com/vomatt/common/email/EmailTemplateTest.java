package com.vomatt.common.email;

import com.vomatt.common.config.I18nConfig;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Year;
import java.util.Locale;

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
        assertThat(html).contains("123456", "感謝您註冊", "3 分鐘", "© " + Year.now().getValue() + " Vomatt").doesNotContain("??");
    }

    @Test
    void shouldRenderLoginEmailWhenLocaleIsEn() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        emailService.sendVerificationEmail("a@example.com", "654321");

        String html = sentHtml();
        assertThat(html).contains("654321", "complete your login", "expire in 3 minutes",
                "© " + Year.now().getValue() + " Vomatt").doesNotContain("??", "pre-signup");
    }

    @Test
    void shouldRenderWelcomeEmailInBothLocales() throws Exception {
        emailService.sendWelcomeEmail("a@example.com", "Amy", ZH_TW);
        assertThat(sentHtml()).contains("您好 Amy", "投下您的第一票").doesNotContain("??");

        emailService.sendWelcomeEmail("b@example.com", "Bob", Locale.ENGLISH);
        assertThat(sentHtml()).contains("Hello Bob", "cast your first Ballot").doesNotContain("??");
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        return (String) captor.getValue().getContent();
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
        ReflectionTestUtils.setField(service, "otpTtlSeconds", 180L);
        return service;
    }
}

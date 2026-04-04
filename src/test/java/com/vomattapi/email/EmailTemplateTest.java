package com.vomattapi.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestPropertySource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {"app.email.enabled=true"})
public class EmailTemplateTest {

    @Test
    public void testPreSignupEmailTemplateWithI18n() {
        // Setup template engine
        TemplateEngine templateEngine = createTemplateEngine();

        // Setup message source
        MessageSource messageSource = createMessageSource();

        // Test Chinese locale
        Context context = new Context(Locale.TRADITIONAL_CHINESE);
        context.setVariable("verificationCode", "123456");

        String htmlContent = templateEngine.process("email/pre-signup-email-verification", context);

        assertNotNull(htmlContent);
        assertTrue(htmlContent.contains("123456"));
        assertTrue(htmlContent.contains("電子郵件驗證") || htmlContent.contains("Email Verification"));
    }

    @Test
    public void testLoginVerificationEmailTemplateWithI18n() {
        // Setup template engine
        TemplateEngine templateEngine = createTemplateEngine();

        // Setup message source
        MessageSource messageSource = createMessageSource();

        // Test English locale
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("verificationCode", "654321");
        context.setVariable("isPreSignup", false);

        String htmlContent = templateEngine.process("email/email-verification", context);

        assertNotNull(htmlContent);
        assertTrue(htmlContent.contains("654321"));
        assertTrue(htmlContent.contains("Email Verification") || htmlContent.contains("電子郵件驗證"));
    }

    private TemplateEngine createTemplateEngine() {
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setMessageSource(createMessageSource());

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);

        templateEngine.setTemplateResolver(templateResolver);
        return templateEngine;
    }

    private MessageSource createMessageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
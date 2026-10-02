package com.vomatt.common.email;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以純 Thymeleaf engine（不啟動 Spring context）渲染 email 模板，驗證驗證碼與 i18n 標題正確輸出。
 * message source 與 {@code I18nConfig} 相同：{@code classpath:i18n/messages}。
 */
class EmailTemplateTest {

    private final TemplateEngine templateEngine = createTemplateEngine();

    @Test
    void preSignupEmailTemplate_zhTW_rendersCodeAndLocalizedTitle() {
        Context context = new Context(Locale.TRADITIONAL_CHINESE);
        context.setVariable("verificationCode", "123456");

        String htmlContent = templateEngine.process("email/pre-signup-email-verification", context);

        assertThat(htmlContent).contains("123456");
        assertThat(htmlContent).contains("電子郵件驗證");
    }

    @Test
    void loginVerificationEmailTemplate_en_rendersCodeAndLocalizedTitle() {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("verificationCode", "654321");
        context.setVariable("isPreSignup", false);

        String htmlContent = templateEngine.process("email/email-verification", context);

        assertThat(htmlContent).contains("654321");
        assertThat(htmlContent).contains("Email Verification");
    }

    private static TemplateEngine createTemplateEngine() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setMessageSource(messageSource);
        engine.setTemplateResolver(templateResolver);
        return engine;
    }
}

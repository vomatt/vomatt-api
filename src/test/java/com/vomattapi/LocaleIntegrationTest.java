package com.vomattapi;

import com.vomattapi.application.service.shared.LocaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class LocaleIntegrationTest {

    private final LocaleService localeService = new LocaleService();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    public void testLocaleDetectionFromAcceptLanguageHeader() {
        // Spring MVC 的 LocaleResolver 會將 request locale 設定到 LocaleContextHolder
        // 這裡模擬此行為，設定 English locale
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        assertEquals("en", detectedLocale.getLanguage());
        assertTrue(localeService.isEnglishLocale());
        assertFalse(localeService.isChineseLocale());
    }

    @Test
    public void testLocaleDetectionWithChineseHeader() {
        // 模擬 Spring MVC LocaleResolver 設定繁中 locale
        LocaleContextHolder.setLocale(Locale.TRADITIONAL_CHINESE);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        assertEquals("zh", detectedLocale.getLanguage());
        assertTrue(localeService.isChineseLocale());
        assertFalse(localeService.isEnglishLocale());
    }

    @Test
    public void testDefaultLocaleWhenNoRequest() {
        // 清除所有 locale 與 request context，service 應回傳預設繁中
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.setLocale(Locale.ROOT);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        // 預設為繁中，驗證語言代碼
        assertEquals("zh", detectedLocale.getLanguage());
        assertTrue(localeService.isChineseLocale());
    }

    @Test
    public void testLocaleServiceFallbackBehavior() {
        // 清除 locale context，讓 getLocaleFromRequest(null) 回傳預設值
        LocaleContextHolder.setLocale(Locale.ROOT);

        Locale locale = localeService.getLocaleFromRequest(null);

        // 預設回傳繁中
        assertEquals("zh", locale.getLanguage());

        // 無 Accept-Language header 的 request，MockHttpServletRequest 預設 locale 為 en
        MockHttpServletRequest request = new MockHttpServletRequest();
        locale = localeService.getLocaleFromRequest(request);
        assertNotNull(locale);
    }
}

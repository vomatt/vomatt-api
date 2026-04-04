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
        // Spring MVC's LocaleResolver sets request locale to LocaleContextHolder
        // Simulate this behavior by setting English locale
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        assertEquals("en", detectedLocale.getLanguage());
        assertTrue(localeService.isEnglishLocale());
        assertFalse(localeService.isChineseLocale());
    }

    @Test
    public void testLocaleDetectionWithChineseHeader() {
        // Simulate Spring MVC LocaleResolver setting Traditional Chinese locale
        LocaleContextHolder.setLocale(Locale.TRADITIONAL_CHINESE);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        assertEquals("zh", detectedLocale.getLanguage());
        assertTrue(localeService.isChineseLocale());
        assertFalse(localeService.isEnglishLocale());
    }

    @Test
    public void testDefaultLocaleWhenNoRequest() {
        // Clear all locale and request context, service should return default Traditional Chinese
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.setLocale(Locale.ROOT);

        Locale detectedLocale = localeService.getCurrentRequestLocale();

        // Default is Traditional Chinese, verify language code
        assertEquals("zh", detectedLocale.getLanguage());
        assertTrue(localeService.isChineseLocale());
    }

    @Test
    public void testLocaleServiceFallbackBehavior() {
        // Clear locale context, let getLocaleFromRequest(null) return default value
        LocaleContextHolder.setLocale(Locale.ROOT);

        Locale locale = localeService.getLocaleFromRequest(null);

        // Default return Traditional Chinese
        assertEquals("zh", locale.getLanguage());

        // Request without Accept-Language header, MockHttpServletRequest defaults to en locale
        MockHttpServletRequest request = new MockHttpServletRequest();
        locale = localeService.getLocaleFromRequest(request);
        assertNotNull(locale);
    }
}

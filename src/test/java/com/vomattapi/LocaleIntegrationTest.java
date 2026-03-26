package com.vomattapi;

import com.vomattapi.application.service.shared.LocaleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureWebMvc
@TestPropertySource(properties = {"app.email.enabled=true"})
public class LocaleIntegrationTest {

    @Autowired
    private LocaleService localeService;

    @Test
    public void testLocaleDetectionFromAcceptLanguageHeader() {
        // Setup mock request with English locale
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US,en;q=0.9");

        // Set up request context
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);

        try {
            Locale detectedLocale = localeService.getCurrentRequestLocale();

            // Should detect English from Accept-Language header
            assertEquals("en", detectedLocale.getLanguage());
            assertTrue(localeService.isEnglishLocale());
            assertFalse(localeService.isChineseLocale());

        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    public void testLocaleDetectionWithChineseHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-TW,zh;q=0.8,en;q=0.6");

        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);

        try {
            Locale detectedLocale = localeService.getCurrentRequestLocale();

            // Should detect Chinese from Accept-Language header
            assertEquals("zh", detectedLocale.getLanguage());
            assertTrue(localeService.isChineseLocale());
            assertFalse(localeService.isEnglishLocale());

        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    public void testDefaultLocaleWhenNoRequest() {
        // Clear any existing request context
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();

        // Should fall back to Traditional Chinese as default
        Locale detectedLocale = localeService.getCurrentRequestLocale();
        assertEquals(Locale.TRADITIONAL_CHINESE, detectedLocale);
        assertTrue(localeService.isChineseLocale());
    }

    @Test
    public void testLocaleServiceFallbackBehavior() {
        // Test with null request
        Locale locale = localeService.getLocaleFromRequest(null);
        assertEquals(Locale.TRADITIONAL_CHINESE, locale);

        // Test with request without locale
        MockHttpServletRequest request = new MockHttpServletRequest();
        locale = localeService.getLocaleFromRequest(request);
        assertEquals(Locale.TRADITIONAL_CHINESE, locale);
    }
}
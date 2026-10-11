package com.vomatt.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Three-tier locale resolution:
 * 1. Accept-Language header (current implementation)
 * 2. zh-TW fallback (default)
 *
 * Phase 2: logged-in users can get their preferred locale from Profile.preferredLocale;
 * this requires overriding LocaleContextHolder after the SecurityContext is ready (HandlerInterceptor.preHandle).
 */
public class VomattLocaleResolver extends AcceptHeaderLocaleResolver {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("zh-TW");

    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.forLanguageTag("zh-TW"),
            Locale.forLanguageTag("zh-CN"),
            Locale.ENGLISH
    );

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        Locale resolved = Locale.lookup(Locale.LanguageRange.parse(acceptLanguage), SUPPORTED_LOCALES);
        return resolved != null ? resolved : DEFAULT_LOCALE;
    }
}

package com.vomatt.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 三層 Locale 解析：
 * 1. Accept-Language header（當前實作）
 * 2. zh-TW fallback（預設）
 *
 * Phase 2：已登入用戶可從 Profile.preferredLocale 取得偏好語系，
 * 需在 SecurityContext 就緒後（HandlerInterceptor.preHandle）覆寫 LocaleContextHolder。
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

package com.vomattapi.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * Service for handling locale detection and resolution
 */
@Service
@RequiredArgsConstructor
public class LocaleService {

    /**
     * Get locale from current HTTP request context
     * Falls back to Traditional Chinese if no locale found or not in web context
     */
    public Locale getCurrentRequestLocale() {
        try {
            // Try to get locale from Spring's LocaleContextHolder first
            Locale locale = LocaleContextHolder.getLocale();
            if (locale != null && !Locale.ROOT.equals(locale)) {
                return locale;
            }

            // Try to get from HTTP request
            ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                locale = request.getLocale();

                if (locale != null && !Locale.ROOT.equals(locale)) {
                    return locale;
                }
            }
        } catch (Exception e) {
            // Ignore and fall back to default
        }

        // Default fallback to Traditional Chinese
        return Locale.TRADITIONAL_CHINESE;
    }

    /**
     * Get locale from Accept-Language header or fall back to default
     */
    public Locale getLocaleFromRequest(HttpServletRequest request) {
        if (request == null) {
            return getCurrentRequestLocale();
        }

        Locale locale = request.getLocale();
        return locale != null && !Locale.ROOT.equals(locale)
            ? locale
            : Locale.TRADITIONAL_CHINESE;
    }

    /**
     * Determine if current locale is Chinese (Simplified or Traditional)
     */
    public boolean isChineseLocale() {
        Locale locale = getCurrentRequestLocale();
        return Locale.CHINESE.getLanguage().equals(locale.getLanguage()) ||
               Locale.SIMPLIFIED_CHINESE.equals(locale) ||
               Locale.TRADITIONAL_CHINESE.equals(locale);
    }

    /**
     * Determine if current locale is English
     */
    public boolean isEnglishLocale() {
        Locale locale = getCurrentRequestLocale();
        return Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
    }
}
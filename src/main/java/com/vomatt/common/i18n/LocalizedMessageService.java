package com.vomatt.common.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LocalizedMessageService {

    private final MessageSource messageSource;

    public String resolve(MessageKey key, Object... args) {
        return messageSource.getMessage(key.code(), args, LocaleContextHolder.getLocale());
    }

    /**
     * Used by GlobalExceptionHandler to handle legacy hard-coded strings:
     * if code is a valid key, return the translation; otherwise return the original string (backward compatible).
     */
    public String resolveOrFallback(String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}

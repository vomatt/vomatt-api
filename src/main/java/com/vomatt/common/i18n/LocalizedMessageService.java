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
     * 供 GlobalExceptionHandler 處理舊有寫死字串時使用：
     * 若 code 為有效 key 則回翻譯結果，否則直接回傳原字串（向下相容）。
     */
    public String resolveOrFallback(String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}

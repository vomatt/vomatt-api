package com.vomatt.common.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LocalizedMessageService {

    private final MessageSource messageSource;

    public String resolve(MessageKey key, Object... args) {
        return messageSource.getMessage(key.code(), args, LocaleContextHolder.getLocale());
    }
}

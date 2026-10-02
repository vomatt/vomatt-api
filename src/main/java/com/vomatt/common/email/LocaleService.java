package com.vomatt.common.email;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class LocaleService {

    public Locale getCurrentRequestLocale() {
        return LocaleContextHolder.getLocale();
    }
}

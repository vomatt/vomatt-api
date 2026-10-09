package com.vomatt.common.security;

import com.vomatt.common.config.I18nConfig;
import tools.jackson.databind.json.JsonMapper;

/** Wires the real error writer with real i18n bundles for filter-level tests. */
public final class SecurityTestSupport {

    private SecurityTestSupport() {
    }

    public static SecurityErrorWriter errorWriter() {
        I18nConfig i18n = new I18nConfig();
        return new SecurityErrorWriter(JsonMapper.builder().build(), i18n.messageSource(), i18n.localeResolver());
    }
}

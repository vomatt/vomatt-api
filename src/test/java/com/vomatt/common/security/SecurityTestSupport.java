package com.vomatt.common.security;

import com.vomatt.common.config.I18nConfig;
import com.vomatt.common.i18n.LocalizedMessageService;
import tools.jackson.databind.json.JsonMapper;

/** Wires the real error writer with real i18n bundles for filter-level tests. */
public final class SecurityTestSupport {

    private SecurityTestSupport() {
    }

    public static SecurityErrorWriter errorWriter() {
        return new SecurityErrorWriter(JsonMapper.builder().build(),
                new LocalizedMessageService(new I18nConfig().messageSource()));
    }
}

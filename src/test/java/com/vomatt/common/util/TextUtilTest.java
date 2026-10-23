package com.vomatt.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilTest {

    @Test
    void shouldStripOrNullWhenBlank() {
        assertThat(TextUtil.blankToNull(null)).isNull();
        assertThat(TextUtil.blankToNull("  ")).isNull();
        assertThat(TextUtil.blankToNull(" tech ")).isEqualTo("tech");
    }

    @Test
    void shouldFormatUtcDayWhenUsedAsCounterSuffix() {
        assertThat(TextUtil.utcDay()).matches("\\d{4}-\\d{2}-\\d{2}")
                .isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
    }

    @Test
    void shouldMaskEmailAndPhoneWhenLogged() {
        assertThat(TextUtil.maskEmail("carol@example.com")).isEqualTo("c***@example.com");
        assertThat(TextUtil.maskEmail("broken")).isEqualTo("***");
        assertThat(TextUtil.maskEmail(null)).isEqualTo("***");
        assertThat(TextUtil.maskPhone("+886912345678")).isEqualTo("+88****78");
        // Short numbers keep at least 2 characters hidden
        assertThat(TextUtil.maskPhone("+1234567")).isEqualTo("+12****67");
        assertThat(TextUtil.maskPhone("12345")).isEqualTo("***");
        assertThat(TextUtil.maskPhone(null)).isEqualTo("***");
    }
}

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
}

package com.vomatt.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppKeyTest {

    private static final String SECRET = "app-key-test-secret-0123456789-0123456789";

    @Test
    void shouldDeriveSameKeyForSameLabelAndDifferentKeysForDifferentLabels() {
        AppKey appKey = new AppKey(SECRET);

        assertThat(appKey.derive("otp")).isEqualTo(new AppKey(SECRET).derive("otp")).hasSize(32);
        assertThat(appKey.derive("otp")).isNotEqualTo(appKey.derive("index"));
        assertThat(appKey.derive("otp")).isNotEqualTo(new AppKey(SECRET + "x").derive("otp"));
    }

    @Test
    void shouldRejectShortKeyWhenConstructed() {
        assertThatThrownBy(() -> new AppKey("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_KEY");
        assertThatThrownBy(() -> new AppKey(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldHashDeterministicallyWhenHexHelpersAreUsed() {
        assertThat(AppKey.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(AppKey.hmacHex("key".getBytes(), "data"))
                .isEqualTo("5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0");
    }
}

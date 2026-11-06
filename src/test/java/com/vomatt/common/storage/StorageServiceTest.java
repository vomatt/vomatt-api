package com.vomatt.common.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

@DisplayName("StorageService")
class StorageServiceTest {

    // Nothing listens on port 1: every call fails to connect
    private final StorageService unreachable = new StorageService("http://127.0.0.1:1", "auto", "vomatt", "k", "s", true);

    @AfterEach
    void tearDown() {
        unreachable.close();
    }

    @Test
    @DisplayName("should answer 503 storage_unavailable when the bucket is unreachable on put or get")
    void shouldThrowServiceUnavailableWhenBucketUnreachable() {
        for (Runnable call : new Runnable[] {
                () -> unreachable.put("avatars/a.jpg", new byte[] { 1 }, "image/jpeg"),
                () -> unreachable.get("avatars/a.jpg") }) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
                org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                org.assertj.core.api.Assertions.assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_STORAGE_UNAVAILABLE);
            });
        }
    }

    @Test
    @DisplayName("should answer 503 storage_unavailable when storage is not configured")
    void shouldThrowServiceUnavailableWhenNotConfigured() {
        StorageService unconfigured = new StorageService("", "auto", "", "", "", false);

        assertThatThrownBy(() -> unconfigured.get("avatars/a.jpg")).isInstanceOfSatisfying(ApiException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_STORAGE_UNAVAILABLE));
    }
}

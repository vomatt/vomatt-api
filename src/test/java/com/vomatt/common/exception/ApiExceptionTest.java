package com.vomatt.common.exception;

import com.vomatt.common.i18n.MessageKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiException} unit tests: covers backward-compatible behavior of the existing static factories, plus
 * {@code unprocessable} (422) and {@code withData} carrying structured {@code data}.
 */
class ApiExceptionTest {

    @Test
    void badRequest_hasNullData_backwardCompatible() {
        ApiException ex = ApiException.badRequest(MessageKey.COMMON_VALIDATION_FAILED);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_VALIDATION_FAILED);
        assertThat(ex.getData()).isNull();
    }

    @Test
    void unprocessable_returns422_withNullData() {
        ApiException ex = ApiException.unprocessable(MessageKey.VOTE_NOT_ALLOWED, "foo");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_ALLOWED);
        assertThat(ex.getMessageArgs()).containsExactly("foo");
        assertThat(ex.getData()).isNull();
    }

    @Test
    void withData_carriesStructuredPayload() {
        record DistanceData(int distanceM) {
        }

        ApiException ex = ApiException.withData(
                HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED, new DistanceData(384));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getData()).isEqualTo(new DistanceData(384));
    }

    @Test
    void withData_nullData_behavesLikePlainException() {
        ApiException ex = ApiException.withData(HttpStatus.CONFLICT, MessageKey.COMMON_CONFLICT, null);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getData()).isNull();
    }
}

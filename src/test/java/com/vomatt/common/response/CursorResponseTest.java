package com.vomatt.common.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

@DisplayName("CursorResponse / Cursor")
class CursorResponseTest {

    record Row(int value, UUID id) {
    }

    @Test
    @DisplayName("should return a next-page cursor and keep only limit items when one extra item is fetched")
    void shouldReturnNextCursorWhenMorePagesExist() {
        List<Row> rows = List.of(new Row(3, UUID.randomUUID()), new Row(2, UUID.randomUUID()), new Row(1, UUID.randomUUID()));

        CursorResponse<Integer> page = CursorResponse.of(rows, 2, r -> Cursor.of(r.value(), r.id()),
                p -> p.stream().map(Row::value).toList());

        assertThat(page.items()).containsExactly(3, 2);
        Cursor next = Cursor.decode(page.nextCursor());
        assertThat(next.longKey()).isEqualTo(2);
        assertThat(next.id()).isEqualTo(rows.get(1).id());
    }

    @Test
    @DisplayName("should return a null cursor when there is no more data")
    void shouldReturnNullCursorWhenLastPage() {
        List<Row> rows = List.of(new Row(1, UUID.randomUUID()));

        CursorResponse<Integer> page = CursorResponse.of(rows, 2, r -> Cursor.of(r.value(), r.id()),
                p -> p.stream().map(Row::value).toList());

        assertThat(page.items()).containsExactly(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should restore the same timestamp and id when a time cursor is encoded and decoded")
    void shouldRoundTripTimeCursor() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 5, 12, 0, 0, 123_456_000, ZoneOffset.ofHours(8));
        UUID id = UUID.randomUUID();

        Cursor decoded = Cursor.decode(Cursor.of(time, id).encode());

        assertThat(decoded.timeKey()).isEqualTo(time.toInstant().atOffset(ZoneOffset.UTC));
        assertThat(decoded.timeKey().toInstant()).isEqualTo(time.toInstant());
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("should throw 400 COMMON_CURSOR_INVALID when cursor is malformed")
    void shouldRejectMalformedCursor() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID);
                });
    }

    @Test
    @DisplayName("should throw 400 COMMON_CURSOR_INVALID when cursor sort key kind mismatches (e.g. a cursor from another list)")
    void shouldRejectCursorWhenKeyKindMismatch() {
        Cursor fromTagList = Cursor.decode(Cursor.of(42L, UUID.randomUUID()).encode());
        Cursor fromUserList = Cursor.decode(Cursor.of("alice", UUID.randomUUID()).encode());

        assertThatThrownBy(fromTagList::timeKey)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
        assertThatThrownBy(fromUserList::longKey)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
    }

    @Test
    @DisplayName("should clamp limit to 1..50 and default to 20 when unspecified")
    void shouldClampLimit() {
        assertThat(CursorResponse.limit(null)).isEqualTo(20);
        assertThat(CursorResponse.limit(0)).isEqualTo(1);
        assertThat(CursorResponse.limit(500)).isEqualTo(50);
        assertThat(CursorResponse.limit(10)).isEqualTo(10);
    }
}

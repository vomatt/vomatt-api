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
    @DisplayName("應該在多取一筆時回傳下一頁 cursor 並只保留 limit 筆")
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
    @DisplayName("應該在沒有更多資料時回傳 null cursor")
    void shouldReturnNullCursorWhenLastPage() {
        List<Row> rows = List.of(new Row(1, UUID.randomUUID()));

        CursorResponse<Integer> page = CursorResponse.of(rows, 2, r -> Cursor.of(r.value(), r.id()),
                p -> p.stream().map(Row::value).toList());

        assertThat(page.items()).containsExactly(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("應該在時間 cursor 編碼後能還原同一時間點與 id")
    void shouldRoundTripTimeCursor() {
        OffsetDateTime time = OffsetDateTime.of(2026, 10, 5, 12, 0, 0, 123_456_000, ZoneOffset.ofHours(8));
        UUID id = UUID.randomUUID();

        Cursor decoded = Cursor.decode(Cursor.of(time, id).encode());

        assertThat(decoded.timeKey()).isEqualTo(time.toInstant().atOffset(ZoneOffset.UTC));
        assertThat(decoded.timeKey().toInstant()).isEqualTo(time.toInstant());
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("應該在 cursor 格式錯誤時拋出 400 COMMON_CURSOR_INVALID")
    void shouldRejectMalformedCursor() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID);
                });
    }

    @Test
    @DisplayName("應該在 cursor 排序鍵種類不符（例如拿到別的列表的 cursor）時拋出 400 COMMON_CURSOR_INVALID")
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
    @DisplayName("應該把 limit 限制在 1 到 50 之間，未指定時為 20")
    void shouldClampLimit() {
        assertThat(CursorResponse.limit(null)).isEqualTo(20);
        assertThat(CursorResponse.limit(0)).isEqualTo(1);
        assertThat(CursorResponse.limit(500)).isEqualTo(50);
        assertThat(CursorResponse.limit(10)).isEqualTo(10);
    }
}

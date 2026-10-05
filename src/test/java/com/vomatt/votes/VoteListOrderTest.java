package com.vomatt.votes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

@DisplayName("VoteListOrder")
class VoteListOrderTest {

    @Test
    @DisplayName("應該預設為 open + newest")
    void shouldDefaultToOpenNewest() {
        assertThat(VoteListOrder.of(null, null)).isEqualTo(VoteListOrder.NEWEST);
        assertThat(VoteListOrder.of("open", "closing")).isEqualTo(VoteListOrder.CLOSING);
        assertThat(VoteListOrder.of("ended", null)).isEqualTo(VoteListOrder.ENDED);
    }

    @Test
    @DisplayName("應該在 status=ended 帶 sort 時拒絕")
    void shouldRejectSortWhenStatusEnded() {
        assertThatThrownBy(() -> VoteListOrder.of("ended", "closing"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_LIST_SORT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("應該拒絕未知的 status 與 sort（含 scheduled）")
    void shouldRejectUnknownValues() {
        assertThatThrownBy(() -> VoteListOrder.of("scheduled", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_STATUS));
        assertThatThrownBy(() -> VoteListOrder.of("open", "popular"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_SORT));
    }
}

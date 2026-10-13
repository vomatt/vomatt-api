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
    @DisplayName("should default to open + newest")
    void shouldDefaultToOpenNewest() {
        assertThat(VoteListOrder.of(null, null)).isEqualTo(VoteListOrder.NEWEST);
        assertThat(VoteListOrder.of("open", "closing")).isEqualTo(VoteListOrder.CLOSING);
        assertThat(VoteListOrder.of("open", "top")).isEqualTo(VoteListOrder.TOP);
        assertThat(VoteListOrder.of(null, "top")).isEqualTo(VoteListOrder.TOP);
        assertThat(VoteListOrder.of("ended", null)).isEqualTo(VoteListOrder.ENDED);
    }

    @Test
    @DisplayName("should reject when status=ended is given with sort")
    void shouldRejectSortWhenStatusEnded() {
        assertThatThrownBy(() -> VoteListOrder.of("ended", "closing"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_LIST_SORT_NOT_ALLOWED));
        assertThatThrownBy(() -> VoteListOrder.of("ended", "top"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_LIST_SORT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("should reject unknown status and sort (including scheduled)")
    void shouldRejectUnknownValues() {
        assertThatThrownBy(() -> VoteListOrder.of("scheduled", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_STATUS));
        assertThatThrownBy(() -> VoteListOrder.of("open", "popular"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_SORT));
    }
}

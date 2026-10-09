package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;

@DisplayName("CommentMapper")
class CommentMapperTest {

    private final CommentMapper mapper = new CommentMapper();

    private VoteComment comment(OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", UUID.randomUUID());
        User author = User.builder().id(UUID.randomUUID()).username("author").build();
        VoteComment c = new VoteComment(vote, author, "hi");
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(c, "createdAt", createdAt);
        ReflectionTestUtils.setField(c, "updatedAt", updatedAt);
        return c;
    }

    @Test
    @DisplayName("應該在新留言（兩個時間戳相差微秒）時不標記為已編輯")
    void shouldNotMarkNewCommentEdited() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at.plusNanos(5_000)), 0, false).isEdited()).isFalse();
    }

    @Test
    @DisplayName("應該在建立後超過一秒才更新時標記為已編輯")
    void shouldMarkEditedWhenUpdatedLater() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at.plusSeconds(5)), 0, false).isEdited()).isTrue();
    }

    @Test
    @DisplayName("應該在時間戳為 null 時不拋例外")
    void shouldNotFailWhenTimestampsMissing() {
        assertThat(mapper.toDto(comment(null, null), 0, false).isEdited()).isFalse();
    }
}

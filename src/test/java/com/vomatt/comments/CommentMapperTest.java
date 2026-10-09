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
    @DisplayName("應該在新留言（兩個時間戳相同）時不標記為已編輯")
    void shouldNotMarkNewCommentEdited() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at), 0, false).isEdited()).isFalse();
    }

    @Test
    @DisplayName("應該在建立後一秒內更新時也標記為已編輯")
    void shouldMarkEditedWhenUpdatedWithinOneSecond() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at.plusNanos(500_000)), 0, false).isEdited()).isTrue();
    }

    @Test
    @DisplayName("應該在留言已刪除時不標記為已編輯")
    void shouldNotMarkDeletedCommentEdited() {
        OffsetDateTime at = OffsetDateTime.now();
        VoteComment c = comment(at, at.plusMinutes(10));
        c.softDelete();

        assertThat(mapper.toDto(c, 0, false).isEdited()).isFalse();
    }

    @Test
    @DisplayName("應該在時間戳為 null 時不拋例外")
    void shouldNotFailWhenTimestampsMissing() {
        assertThat(mapper.toDto(comment(null, null), 0, false).isEdited()).isFalse();
    }
}

package com.vomatt.comments;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommentMapper")
class CommentMapperTest {

    private final CommentMapper mapper = new CommentMapper();

    private VoteComment comment(OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", UUID.randomUUID());
        User user = User.builder().id(UUID.randomUUID()).username("mei").build();
        VoteComment comment = new VoteComment(vote, user, "Saturday works");
        ReflectionTestUtils.setField(comment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(comment, "createdAt", createdAt);
        ReflectionTestUtils.setField(comment, "updatedAt", updatedAt);
        return comment;
    }

    @Test
    @DisplayName("時間戳記尚未寫入時不應拋出例外")
    void shouldNotFailBeforeTimestampsAreSet() {
        CommentDto dto = mapper.toDto(comment(null, null), 0, false);
        assertThat(dto.isEdited()).isFalse();
    }

    @Test
    @DisplayName("更新時間晚於建立時間時應標示為已編輯")
    void shouldMarkEditedComments() {
        OffsetDateTime created = OffsetDateTime.now().minusMinutes(5);
        assertThat(mapper.toDto(comment(created, created), 0, false).isEdited()).isFalse();
        assertThat(mapper.toDto(comment(created, created.plusNanos(30_000)), 0, false).isEdited()).isFalse();
        assertThat(mapper.toDto(comment(created, OffsetDateTime.now()), 0, false).isEdited()).isTrue();
    }
}

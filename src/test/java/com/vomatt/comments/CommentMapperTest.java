package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.comments.dto.CommentDto;
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
    @DisplayName("should not mark as edited when comment is new (both timestamps equal)")
    void shouldNotMarkNewCommentEdited() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at), 0, false, java.util.Map.of(), com.vomatt.users.PublicName.NONE).isEdited()).isFalse();
    }

    @Test
    @DisplayName("should mark as edited when updated within one second of creation")
    void shouldMarkEditedWhenUpdatedWithinOneSecond() {
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(mapper.toDto(comment(at, at.plusNanos(500_000)), 0, false, java.util.Map.of(), com.vomatt.users.PublicName.NONE).isEdited()).isTrue();
    }

    @Test
    @DisplayName("should not mark as edited when comment is deleted")
    void shouldNotMarkDeletedCommentEdited() {
        OffsetDateTime at = OffsetDateTime.now();
        VoteComment c = comment(at, at.plusMinutes(10));
        c.softDelete();

        assertThat(mapper.toDto(c, 0, false, java.util.Map.of(), com.vomatt.users.PublicName.NONE).isEdited()).isFalse();
    }

    @Test
    @DisplayName("should not throw when timestamps are null")
    void shouldNotFailWhenTimestampsMissing() {
        assertThat(mapper.toDto(comment(null, null), 0, false, java.util.Map.of(), com.vomatt.users.PublicName.NONE).isEdited()).isFalse();
    }

    @Test
    @DisplayName("should carry the author's public names, and none on a deleted placeholder")
    void shouldSetAuthorNamesUnlessDeleted() {
        OffsetDateTime at = OffsetDateTime.now();
        com.vomatt.users.PublicName name = new com.vomatt.users.PublicName("Kai", "Kai", "Lin");
        VoteComment deleted = comment(at, at);
        deleted.softDelete();

        CommentDto shown = mapper.toDto(comment(at, at), 0, false, java.util.Map.of(), name);
        CommentDto placeholder = mapper.toDto(deleted, 0, false, java.util.Map.of(), name);

        assertThat(List.of(shown.getAuthorDisplayName(), shown.getAuthorFirstName(), shown.getAuthorLastName()))
                .containsExactly("Kai", "Kai", "Lin");
        assertThat(placeholder.getAuthorDisplayName()).isNull();
        assertThat(placeholder.getAuthorFirstName()).isNull();
    }
}

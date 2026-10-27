package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import com.vomatt.votes.VoteServiceSlice;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteCommentRepository;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("Comment edited flag (Postgres)")
class CommentEditedPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteCommentRepository commentRepository;
    @Autowired CommentMapper commentMapper;

    private User user;
    private Vote poll;

    @BeforeEach
    void setUp() {
        user = persistUser("ce-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private UUID post(String text) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        CommentDto dto = commentService.createComment(poll.getId().toString(), user.getId().toString(), request);
        em.flush();
        em.clear();
        return UUID.fromString(dto.getId());
    }

    // Timestamps and the edited flag only: likes are passed as none, not read
    private CommentDto reloadIgnoringLikes(UUID id) {
        em.flush();
        em.clear();
        return commentMapper.toDto(commentRepository.findById(id).orElseThrow(), 0, false);
    }

    @Test
    @DisplayName("should have identical created and updated times when a new comment is written")
    void shouldStoreEqualTimestampsOnInsert() {
        CommentDto dto = reloadIgnoringLikes(post("hi"));

        assertThat(dto.getUpdatedAt()).isEqualTo(dto.getCreatedAt());
        assertThat(dto.isEdited()).isFalse();
    }

    @Test
    @DisplayName("should mark as edited when edited immediately after creation")
    void shouldMarkEditedWhenUpdatedImmediately() {
        UUID id = post("hi");
        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("hi, fixed");
        commentService.updateComment(id, user.getId().toString(), request);

        assertThat(reloadIgnoringLikes(id).isEdited()).isTrue();
    }

    @Test
    @DisplayName("should keep likedByCurrentUser true in the edit response when the author liked their own comment")
    void shouldKeepLikedStateWhenAuthorEditsLikedComment() {
        UUID id = post("hi");
        commentService.likeComment(id, user.getId().toString());
        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("hi, fixed");

        CommentDto edited = commentService.updateComment(id, user.getId().toString(), request);

        assertThat(edited.isLikedByCurrentUser()).isTrue();
        assertThat(edited.getLikeCount()).isEqualTo(1);
    }
}

package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;

@Import({ VoteCommentService.class, CommentMapper.class })
@DisplayName("Comment edited flag (Postgres)")
class CommentEditedPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;

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

    private CommentDto reload(UUID id) {
        em.flush();
        em.clear();
        return commentService.getComment(id, user.getId().toString());
    }

    @Test
    @DisplayName("應該在新留言寫入後建立與更新時間完全相同")
    void shouldStoreEqualTimestampsOnInsert() {
        CommentDto dto = reload(post("hi"));

        assertThat(dto.getUpdatedAt()).isEqualTo(dto.getCreatedAt());
        assertThat(dto.isEdited()).isFalse();
    }

    @Test
    @DisplayName("應該在建立後立即編輯時標記為已編輯")
    void shouldMarkEditedWhenUpdatedImmediately() {
        UUID id = post("hi");
        UpdateCommentRequest request = new UpdateCommentRequest();
        request.setText("hi, fixed");
        commentService.updateComment(id, user.getId().toString(), request);

        assertThat(reload(id).isEdited()).isTrue();
    }
}

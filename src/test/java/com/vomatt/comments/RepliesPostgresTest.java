package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteCommentRepository;

@Import({ VoteCommentService.class, CommentMapper.class })
@DisplayName("Replies (Postgres)")
class RepliesPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired VoteCommentRepository commentRepository;

    private User user;
    private Vote poll;

    @BeforeEach
    void setUp() {
        user = persistUser("rp-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(user, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private CommentDto post(String text, String parentId) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText(text);
        request.setParentId(parentId == null ? null : UUID.fromString(parentId));
        CommentDto dto = commentService.createComment(poll.getId().toString(), user.getId().toString(), request);
        em.flush();
        return dto;
    }

    private List<CommentDto> topLevel() {
        em.clear();
        return commentService.getCommentsByVote(poll.getId().toString(), null, null, user.getId().toString()).items();
    }

    private List<String> replies(String rootId, int limit) {
        em.clear();
        List<String> texts = new ArrayList<>();
        String cursor = null;
        do {
            CursorResponse<CommentDto> page = commentService.getReplies(poll.getId().toString(),
                    UUID.fromString(rootId), cursor, limit, null);
            page.items().forEach(c -> texts.add(c.getText()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return texts;
    }

    @Test
    @DisplayName("應該在回覆「回覆」時掛到同一則頂層留言下")
    void shouldAttachReplyToRootWhenReplyingToReply() {
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());

        CommentDto nested = post("reply to reply", reply.getId());

        assertThat(nested.getParentId()).isEqualTo(root.getId());
        assertThat(topLevel()).extracting(CommentDto::getText).containsExactly("root");
        assertThat(topLevel().getFirst().getReplyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("應該讓回覆依舊到新分頁")
    void shouldListRepliesOldestFirst() {
        CommentDto root = post("root", null);
        for (int i = 0; i < 5; i++) {
            post("r" + i, root.getId());
        }

        assertThat(replies(root.getId(), 2)).containsExactly("r0", "r1", "r2", "r3", "r4");
    }

    @Test
    @DisplayName("應該在刪除有回覆的留言時保留佔位，回覆全刪後佔位消失")
    void shouldKeepPlaceholderWhenDeletedCommentHasReplies() {
        CommentDto root = post("root", null);
        CommentDto reply = post("reply", root.getId());

        commentService.deleteComment(UUID.fromString(root.getId()), user.getId().toString());
        em.flush();
        CommentDto placeholder = topLevel().getFirst();
        assertThat(placeholder.isDeleted()).isTrue();
        assertThat(placeholder.getText()).isNull();
        assertThat(placeholder.getAuthor()).isNull();
        assertThat(placeholder.getReplyCount()).isEqualTo(1);

        commentService.deleteComment(UUID.fromString(reply.getId()), user.getId().toString());
        em.flush();
        assertThat(topLevel()).isEmpty();
    }

    @Test
    @DisplayName("應該讓 commentCount 含回覆、不含已刪除")
    void shouldCountRepliesInCommentCount() {
        CommentDto root = post("root", null);
        post("reply", root.getId());
        CommentDto gone = post("gone", root.getId());
        commentService.deleteComment(UUID.fromString(gone.getId()), user.getId().toString());
        em.flush();

        assertThat(commentRepository.countVisibleByVoteIds(List.of(poll.getId())).getFirst().getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("應該拒絕回覆其他 Poll 的留言")
    void shouldRejectReplyToCommentOfAnotherPoll() {
        Vote other = persistPoll(user, OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(1), "X", "Y");
        em.flush();
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText("elsewhere");
        CommentDto foreign = commentService.createComment(other.getId().toString(), user.getId().toString(), request);

        assertThatThrownBy(() -> post("bad", foreign.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_PARENT_INVALID));
    }
}

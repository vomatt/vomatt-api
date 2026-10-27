package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.ReportCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommentReportReason;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

/** Liking, replying and reporting see the same Comments the lists show: none by a user the caller blocked. */
@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class })
@DisplayName("Comment writes and blocks (Postgres)")
class CommentBlockPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;

    private User viewer;
    private User blocked;
    private Vote poll;
    private VoteComment comment;

    @BeforeEach
    void setUp() {
        viewer = persistUser("cb-viewer-" + UUID.randomUUID());
        blocked = persistUser("cb-blocked-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(viewer, now.minusHours(1), now.plusDays(1), "A", "B");
        comment = new VoteComment(poll, blocked, "hi");
        em.persist(comment);
        em.flush();
    }

    private void block(User blocker, User target) {
        em.persist(new UserBlock(blocker, target));
        em.flush();
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));
    }

    private ReportCommentRequest spam() {
        return new ReportCommentRequest(CommentReportReason.SPAM, null);
    }

    private void reply(VoteComment parent) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setText("re");
        request.setParentId(parent.getId());
        commentService.createComment(poll.getId().toString(), viewer.getId().toString(), request);
    }

    @Test
    @DisplayName("should 404 liking, replying to and reporting a Comment by a user the caller blocked")
    void shouldReturnNotFoundWhenCallerBlockedAuthor() {
        block(viewer, blocked);
        String viewerId = viewer.getId().toString();

        assertNotFound(() -> commentService.likeComment(comment.getId(), viewerId));
        assertNotFound(() -> reply(comment));
        assertNotFound(() -> commentService.reportComment(comment.getId(), viewerId, spam()));
    }

    @Test
    @DisplayName("should let the caller take back a like after blocking the author, as it reveals nothing")
    void shouldUnlikeWhenCallerBlockedAuthor() {
        String viewerId = viewer.getId().toString();
        commentService.likeComment(comment.getId(), viewerId);
        block(viewer, blocked);

        assertThatCode(() -> commentService.unlikeComment(comment.getId(), viewerId)).doesNotThrowAnyException();
        assertThat(em.createNativeQuery("SELECT COUNT(*) FROM vomatt.comment_likes WHERE comment_id = :id")
                .setParameter("id", comment.getId()).getSingleResult()).isEqualTo(0L);
    }

    @Test
    @DisplayName("should 404 liking, replying to and reporting a Reply in a thread started by a user the caller blocked")
    void shouldReturnNotFoundWhenCallerBlockedThreadAuthor() {
        User replier = persistUser("cb-replier-" + UUID.randomUUID());
        VoteComment reply = new VoteComment(poll, replier, "re");
        reply.setParent(comment);
        em.persist(reply);
        block(viewer, blocked);
        String viewerId = viewer.getId().toString();

        assertNotFound(() -> commentService.likeComment(reply.getId(), viewerId));
        assertNotFound(() -> reply(reply));
        assertNotFound(() -> commentService.reportComment(reply.getId(), viewerId, spam()));
    }

    @Test
    @DisplayName("should 404 liking, replying to and reporting a Reply by a user the caller blocked under another user's thread")
    void shouldReturnNotFoundWhenCallerBlockedReplyAuthor() {
        User starter = persistUser("cb-starter-" + UUID.randomUUID());
        VoteComment root = new VoteComment(poll, starter, "root");
        em.persist(root);
        VoteComment reply = new VoteComment(poll, blocked, "re");
        reply.setParent(root);
        em.persist(reply);
        block(viewer, blocked);
        String viewerId = viewer.getId().toString();

        assertNotFound(() -> commentService.likeComment(reply.getId(), viewerId));
        assertNotFound(() -> reply(reply));
        assertNotFound(() -> commentService.reportComment(reply.getId(), viewerId, spam()));
        assertThatCode(() -> commentService.likeComment(root.getId(), viewerId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should allow liking and reporting when only the author blocked the caller, as the lists still show it")
    void shouldAllowWhenAuthorBlockedCaller() {
        block(blocked, viewer);
        String viewerId = viewer.getId().toString();

        assertThatCode(() -> commentService.likeComment(comment.getId(), viewerId)).doesNotThrowAnyException();
        assertThatCode(() -> commentService.reportComment(comment.getId(), viewerId, spam()))
                .doesNotThrowAnyException();
    }
}

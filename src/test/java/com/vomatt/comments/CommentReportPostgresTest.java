package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.admin.AdminCommentReportService;
import com.vomatt.admin.AdminMapper;
import com.vomatt.admin.dto.AdminCommentReportResponse;
import com.vomatt.comments.dto.ReportCommentRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommentReportReason;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.votes.VoteServiceSlice;

@Import({ VoteCommentService.class, CommentMapper.class, VoteServiceSlice.class, AdminCommentReportService.class,
        AdminMapper.class })
@DisplayName("Comment reports (Postgres)")
class CommentReportPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteCommentService commentService;
    @Autowired AdminCommentReportService reportService;

    private User author;
    private User reporter;
    private Vote poll;
    private VoteComment comment;

    @BeforeEach
    void setUp() {
        author = persistUser("cr-author-" + UUID.randomUUID());
        reporter = persistUser("cr-reporter-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        poll = persistPoll(author, now.minusHours(1), now.plusDays(1), "A", "B");
        comment = new VoteComment(poll, author, "You are all wrong.");
        em.persist(comment);
        em.flush();
    }

    private void report(User user, VoteComment target, CommentReportReason reason, String note) {
        commentService.reportComment(target.getId(), user.getId().toString(), new ReportCommentRequest(reason, note));
        em.flush();
        em.clear();
    }

    private List<AdminCommentReportResponse> reports() {
        return reportService.listReports(null, 50).content().stream()
                .filter(r -> r.voteId().equals(poll.getId().toString())).toList();
    }

    @Test
    @DisplayName("should list a report with the Comment, its author, the reporter, the reason and the note")
    void shouldListReportWhenCommentReported() {
        report(reporter, comment, CommentReportReason.HARASSMENT, "  Insults everyone.  ");

        assertThat(reports()).singleElement().satisfies(r -> {
            assertThat(r.commentId()).isEqualTo(comment.getId().toString());
            assertThat(r.parentId()).isNull();
            assertThat(r.commentText()).isEqualTo("You are all wrong.");
            assertThat(r.commentDeleted()).isFalse();
            assertThat(r.authorUsername()).isEqualTo(author.getUsername());
            assertThat(r.reporterUsername()).isEqualTo(reporter.getUsername());
            assertThat(r.reason()).isEqualTo(CommentReportReason.HARASSMENT);
            assertThat(r.note()).isEqualTo("Insults everyone.");
            assertThat(r.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("should keep the first report when the same user reports the same Comment again")
    void shouldKeepFirstReportWhenReportedTwice() {
        report(reporter, comment, CommentReportReason.SPAM, null);
        report(reporter, comment, CommentReportReason.HATE, "changed my mind");

        assertThat(reports()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(CommentReportReason.SPAM);
            assertThat(r.note()).isNull();
        });
    }

    @Test
    @DisplayName("should list reports newest first and store a blank note as none")
    void shouldListNewestFirstWhenSeveralUsersReport() {
        User second = persistUser("cr-second-" + UUID.randomUUID());
        report(reporter, comment, CommentReportReason.SPAM, null);
        report(second, comment, CommentReportReason.OTHER, "   ");

        assertThat(reports()).extracting(AdminCommentReportResponse::reporterUsername)
                .containsExactly(second.getUsername(), reporter.getUsername());
        assertThat(reports().getFirst().note()).isNull();
    }

    @Test
    @DisplayName("should reject reporting one's own Comment")
    void shouldRejectReportWhenOwnComment() {
        assertThatThrownBy(() -> report(author, comment, CommentReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_REPORT_SELF));
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("should 404 a deleted Comment and a Comment on a Deleted Poll")
    void shouldReturnNotFoundWhenCommentOrPollDeleted() {
        VoteComment deleted = new VoteComment(poll, author, "gone");
        deleted.softDelete();
        em.persist(deleted);
        em.flush();

        assertThatThrownBy(() -> report(reporter, deleted, CommentReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));

        poll.setDeletedAt(OffsetDateTime.now());
        em.flush();

        assertThatThrownBy(() -> report(reporter, comment, CommentReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should return 404 user.not_found when the signed-in user no longer exists")
    void shouldThrowUserNotFoundWhenReporterIsGone() {
        String gone = UUID.randomUUID().toString();

        assertThatThrownBy(() -> commentService.reportComment(comment.getId(), gone,
                new ReportCommentRequest(CommentReportReason.SPAM, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("should drop a user's reports when the account is deleted")
    void shouldDropReportsWhenReporterDeleted() {
        report(reporter, comment, CommentReportReason.SPAM, null);

        em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", reporter.getId()).executeUpdate();

        assertThat(reports()).isEmpty();
    }
}

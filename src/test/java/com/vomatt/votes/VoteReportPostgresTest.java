package com.vomatt.votes;

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

import com.vomatt.admin.AdminMapper;
import com.vomatt.admin.AdminVoteReportService;
import com.vomatt.admin.dto.AdminVoteReportResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.entity.Audience;
import com.vomatt.entity.ReportReason;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.VoteReportRepository;
import com.vomatt.common.dto.ReportRequest;

@Import({ VoteServiceSlice.class, AdminVoteReportService.class, AdminMapper.class })
@DisplayName("Poll reports (Postgres)")
class VoteReportPostgresTest extends PostgresRepositoryTest {

    @Autowired VoteService voteService;
    @Autowired AdminVoteReportService reportService;
    @Autowired VoteReportRepository reportRepository;

    private final OffsetDateTime now = OffsetDateTime.now();
    private User owner;
    private User reporter;
    private Vote poll;

    @BeforeEach
    void setUp() {
        owner = persistUser("vr-owner-" + UUID.randomUUID());
        reporter = persistUser("vr-reporter-" + UUID.randomUUID());
        poll = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        em.flush();
    }

    private void report(User user, Vote target, ReportReason reason, String note) {
        voteService.reportVote(target.getId().toString(), user.getId().toString(), new ReportRequest(reason, note));
        em.flush();
        em.clear();
    }

    private List<AdminVoteReportResponse> reports() {
        return reportService.listReports(null, 50).content().stream()
                .filter(r -> r.voteId().equals(poll.getId().toString())).toList();
    }

    @Test
    @DisplayName("should list a report with the Poll, its owner, the reporter, the reason and the note")
    void shouldListReportWhenPollReported() {
        report(reporter, poll, ReportReason.MISINFORMATION, "  False claim.  ");

        assertThat(reports()).singleElement().satisfies(r -> {
            assertThat(r.voteTitle()).isEqualTo(poll.getTitle());
            assertThat(r.voteDeleted()).isFalse();
            assertThat(r.ownerUsername()).isEqualTo(owner.getUsername());
            assertThat(r.reporterUsername()).isEqualTo(reporter.getUsername());
            assertThat(r.reason()).isEqualTo(ReportReason.MISINFORMATION);
            assertThat(r.note()).isEqualTo("False claim.");
            assertThat(r.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("should keep the first report when the same user reports the same Poll again")
    void shouldKeepFirstReportWhenReportedTwice() {
        report(reporter, poll, ReportReason.SPAM, null);
        report(reporter, poll, ReportReason.HATE, "changed my mind");

        assertThat(reports()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(ReportReason.SPAM);
            assertThat(r.note()).isNull();
        });
    }

    @Test
    @DisplayName("should list reports newest first, store a blank note as none and keep them once the Poll is Deleted")
    void shouldListNewestFirstWhenSeveralUsersReport() {
        User second = persistUser("vr-second-" + UUID.randomUUID());
        report(reporter, poll, ReportReason.SPAM, null);
        report(second, poll, ReportReason.OTHER, "   ");
        em.createNativeQuery("UPDATE vomatt.votes SET deleted_at = now() WHERE id = :id").setParameter("id", poll.getId())
                .executeUpdate();

        assertThat(reports()).extracting(AdminVoteReportResponse::reporterUsername)
                .containsExactly(second.getUsername(), reporter.getUsername());
        assertThat(reports().getFirst().note()).isNull();
        assertThat(reports()).allMatch(AdminVoteReportResponse::voteDeleted);
    }

    @Test
    @DisplayName("should return vote.not_found when the caller cannot reach the Invite-only Poll")
    void shouldReturnNotFoundWhenPollIsInviteOnlyAndCallerIsNotInvitee() {
        poll.setAudience(Audience.INVITE_ONLY);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> report(reporter, poll, ReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("should return vote.not_found when the Poll is Deleted")
    void shouldReturnNotFoundWhenPollDeleted() {
        poll.setDeletedAt(now);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> report(reporter, poll, ReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should return 404 user.not_found when the account is deleted after the filter let the request in")
    void shouldThrowUserNotFoundWhenReporterIsGone() {
        em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", reporter.getId()).executeUpdate();

        assertThatThrownBy(() -> report(reporter, poll, ReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("should insert nothing when the Poll was Deleted after it was looked up")
    void shouldInsertNothingWhenReportedPollDeleted() {
        em.createNativeQuery("UPDATE vomatt.votes SET deleted_at = now() WHERE id = :id").setParameter("id", poll.getId())
                .executeUpdate();

        assertThat(reportRepository.insertIfAbsent(UUIDv7Generator.generateId(), poll.getId(), reporter.getId(),
                ReportReason.SPAM.name(), null)).isZero();
    }

    @Test
    @DisplayName("should return vote.not_found when the Poll owner blocked the caller")
    void shouldReturnNotFoundWhenOwnerBlockedCaller() {
        em.persist(new UserBlock(owner, reporter));
        em.flush();

        assertThatThrownBy(() -> report(reporter, poll, ReportReason.SPAM, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("should return vote.not_found and keep no report when the Poll is Deleted after its lookup")
    void shouldReturnNotFoundWhenPollDeletedAfterLookup() {
        // The managed Poll still reads as live, as it would after a concurrent delete
        em.createNativeQuery("UPDATE vomatt.votes SET deleted_at = now() WHERE id = :id").setParameter("id", poll.getId())
                .executeUpdate();

        assertThatThrownBy(() -> voteService.reportVote(poll.getId().toString(), reporter.getId().toString(),
                new ReportRequest(ReportReason.SPAM, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
        assertThat(reports()).isEmpty();
    }

    @Test
    @DisplayName("should drop a user's reports when the account is deleted")
    void shouldDropReportsWhenReporterDeleted() {
        report(reporter, poll, ReportReason.SPAM, null);

        em.createNativeQuery("DELETE FROM vomatt.users WHERE id = :id").setParameter("id", reporter.getId()).executeUpdate();

        assertThat(reports()).isEmpty();
    }
}

package com.vomatt.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.retention.RetentionJob;
import com.vomatt.common.util.TimeUtil;
import com.vomatt.entity.Gender;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.reports.dto.PollReportResponse;
import com.vomatt.reports.dto.PollReportResponse.Group;
import com.vomatt.repository.PollReportRepository;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.users.CommunityService;
import com.vomatt.users.UserMapper;
import com.vomatt.votes.VoteServiceSlice;

@Import({ VoteServiceSlice.class, PollReportService.class, PollReportRepository.class, CommunityService.class, UserMapper.class,
        RetentionJob.class })
class PollReportPostgresTest extends PostgresRepositoryTest {

    @Autowired PollReportService reportService;
    @MockitoSpyBean PollReportRepository reportRepository;
    @Autowired RetentionJob retentionJob;
    @Autowired TransactionTemplate tx;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    /** An Ended, revealed Pro poll with options A and B; {@code voters} chose A. */
    private Vote endedProPoll(User owner, List<User> voters) {
        Vote poll = persistPoll(owner, now.minusDays(20), now.minusDays(10), "A", "B");
        VoteOption a = option(poll, "A");
        voters.forEach(v -> em.persist(new UserVote(v, poll, a)));
        em.flush();
        em.createNativeQuery("UPDATE vomatt.votes SET pro = TRUE, voter_visibility = 'NOBODY', is_anonymous = TRUE WHERE id = :id")
                .setParameter("id", poll.getId()).executeUpdate();
        em.clear();
        return poll;
    }

    private static VoteOption option(Vote poll, String text) {
        return poll.getOptions().stream().filter(o -> o.getText().equals(text)).findFirst().orElseThrow();
    }

    private List<User> voters(String prefix, int n, LocalDate birthday) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            User u = persistUser(prefix + i + "-" + System.nanoTime());
            u.setBirthday(birthday);
            users.add(u);
        }
        return users;
    }

    @Test
    void shouldKeepSnapshotWhenBirthdayChangesAfterGeneration() {
        User owner = persistUser("rep-owner-" + System.nanoTime());
        // 17 at the Poll's end time, 18 now: the report uses the age at the end time
        LocalDate endDate = TimeUtil.taipeiBusinessDate(now.minusDays(10));
        List<User> voters = voters("rep-young-", 5, endDate.minusYears(18).plusDays(1));
        Vote poll = endedProPoll(owner, voters);

        PollReportResponse first = reportService.getReport(poll.getId().toString(), owner.getId().toString());

        assertThat(first.turnout().ballots()).isEqualTo(5);
        assertThat(first.turnout().invitees()).isNull();
        assertThat(first.options()).extracting(PollReportResponse.OptionCount::count).containsExactly(5, 0);
        assertThat(first.ageBands()).containsExactly(new Group("UNDER_18", 5, List.of(5, 0)));
        assertThat(first.genders()).containsExactly(new Group("NOT_PROVIDED", 5, List.of(5, 0)));
        assertThat(first.timeline().closedAt().toInstant()).isEqualTo(now.minusDays(10).toInstant());
        assertThat(first.timeline().revealedAt().toInstant()).isEqualTo(now.minusDays(10).toInstant());

        em.createNativeQuery("UPDATE vomatt.users SET birthday = DATE '1980-01-01', gender = 'FEMALE' WHERE id IN (:ids)")
                .setParameter("ids", voters.stream().map(User::getId).toList()).executeUpdate();
        em.clear();

        PollReportResponse again = reportService.getReport(poll.getId().toString(), owner.getId().toString());
        assertThat(again).isEqualTo(first);
        assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.poll_reports WHERE vote_id = :id")
                .setParameter("id", poll.getId()).getSingleResult()).intValue()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldIsolateFailedReportAndKeepItsAnonymousBallotsWhenRetentionRuns() {
        List<UUID> userIds = new ArrayList<>();
        // ok: 5 voters, one of them Anonymous (pre-Pro); failing: its report hits a database error; cancelled: no report
        List<Vote> polls = tx.execute(s -> {
            User owner = persistUser("rep-ret-owner-" + System.nanoTime());
            userIds.add(owner.getId());
            List<Vote> created = new ArrayList<>();
            for (String prefix : List.of("rep-ret-ok-", "rep-ret-fail-", "rep-ret-cancel-")) {
                List<User> voters = voters(prefix, prefix.contains("ok") ? 5 : 1, LocalDate.of(1990, 1, 1));
                voters.forEach(v -> {
                    v.setGender(Gender.MALE);
                    userIds.add(v.getId());
                });
                created.add(endedProPoll(owner, voters));
            }
            em.createNativeQuery("UPDATE vomatt.user_votes SET anonymous = TRUE WHERE id IN (SELECT DISTINCT ON (vote_id) id FROM vomatt.user_votes WHERE vote_id IN (:ids))")
                    .setParameter("ids", created.stream().map(Vote::getId).toList()).executeUpdate();
            em.createNativeQuery("UPDATE vomatt.votes SET start_time = end_time WHERE id = :id")
                    .setParameter("id", created.get(2).getId()).executeUpdate();
            return created;
        });
        UUID ok = polls.get(0).getId();
        UUID failing = polls.get(1).getId();
        UUID cancelled = polls.get(2).getId();
        String ownerId = userIds.getFirst().toString();
        // A real database error: aborts the Postgres transaction it runs in
        doAnswer(inv -> em.createNativeQuery("SELECT 1/0").getSingleResult())
                .when(reportRepository).insertIfAbsent(eq(failing), any(), any());
        try {
            RetentionJob.Result result = retentionJob.run(now);

            assertThat(result.pollReports()).isGreaterThanOrEqualTo(1);
            assertThat(report(ok)).isPresent();
            assertThat(report(failing)).isEmpty();
            assertThat(report(cancelled)).isEmpty();
            // Sealed once the report holds them; the failed Poll's wait for its report, the Cancelled one's do not
            assertThat(anonymousOptions(ok)).containsOnlyNulls();
            assertThat(anonymousOptions(failing)).doesNotContainNull().isNotEmpty();
            assertThat(anonymousOptions(cancelled)).containsOnlyNulls();
            PollReportResponse report = tx.execute(s -> reportService.getReport(ok.toString(), ownerId));
            assertThat(report.options()).extracting(PollReportResponse.OptionCount::count).containsExactly(5, 0);
            assertThat(report.genders()).containsExactly(new Group("MALE", 5, List.of(5, 0)));
            assertThatThrownBy(() -> tx.execute(s -> reportService.getReport(cancelled.toString(), ownerId)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.REPORT_CANCELLED));

            org.mockito.Mockito.reset(reportRepository);
            assertThat(retentionJob.run(now).pollReports()).isGreaterThanOrEqualTo(1);
            assertThat(report(failing)).isPresent();
            assertThat(anonymousOptions(failing)).containsOnlyNulls();
        } finally {
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("DELETE FROM vomatt.votes WHERE id IN (:ids)").setParameter("ids", List.of(ok, failing, cancelled)).executeUpdate();
                em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)").setParameter("ids", userIds).executeUpdate();
            });
        }
    }

    private java.util.Optional<String> report(UUID pollId) {
        return tx.execute(s -> reportRepository.findData(pollId));
    }

    @SuppressWarnings("unchecked")
    private List<Object> anonymousOptions(UUID pollId) {
        return tx.execute(s -> em.createNativeQuery("SELECT option_id FROM vomatt.user_votes WHERE vote_id = :id AND anonymous")
                .setParameter("id", pollId).getResultList());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldStoreOneSnapshotWhenFirstReadsRace() throws Exception {
        List<UUID> userIds = new ArrayList<>();
        Vote poll = tx.execute(s -> {
            User owner = persistUser("rep-race-owner-" + System.nanoTime());
            List<User> voters = voters("rep-race-", 5, LocalDate.of(1985, 5, 5));
            userIds.add(owner.getId());
            voters.forEach(v -> userIds.add(v.getId()));
            return endedProPoll(owner, voters);
        });
        String pollId = poll.getId().toString();
        String ownerId = userIds.getFirst().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // A stores its snapshot and holds the transaction open
            Future<PollReportResponse> a = pool.submit(() -> tx.execute(s -> {
                PollReportResponse r = reportService.getReport(pollId, ownerId);
                inserted.countDown();
                await(release);
                return r;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            // B finds no committed row, generates its own and waits on A's key
            Future<PollReportResponse> b = pool.submit(() -> tx.execute(s -> reportService.getReport(pollId, ownerId)));
            assertThat(waitForBlockedInsert()).as("B waits on A's uncommitted row").isTrue();
            release.countDown();

            PollReportResponse ra = a.get(10, TimeUnit.SECONDS);
            PollReportResponse rb = b.get(10, TimeUnit.SECONDS);
            assertThat(rb.generatedAt()).isEqualTo(ra.generatedAt());
            Number rows = tx.execute(s -> (Number) em.createNativeQuery("SELECT COUNT(*) FROM vomatt.poll_reports WHERE vote_id = :id")
                    .setParameter("id", poll.getId()).getSingleResult());
            assertThat(rows.intValue()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("DELETE FROM vomatt.votes WHERE id = :id").setParameter("id", poll.getId()).executeUpdate();
                em.createNativeQuery("DELETE FROM vomatt.users WHERE id IN (:ids)").setParameter("ids", userIds).executeUpdate();
            });
        }
    }

    private boolean waitForBlockedInsert() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Number blocked = tx.execute(s -> (Number) em.createNativeQuery("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO vomatt.poll_reports%'""").getSingleResult());
            if (blocked.intValue() == 1) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;

/** Runs the real db/migrations/2026-10-26-notification-reads-by-kind.sql inside the rolled-back test transaction. */
@DisplayName("2026-10-26-notification-reads-by-kind.sql (Postgres)")
class NotificationReadsByKindMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migrations/2026-10-26-notification-reads-by-kind.sql";

    // The table as the old schema.sql created it before the feed, one read row per (user, Poll)
    private static final List<String> TO_POLL_READS = List.of(
            "DROP TABLE vomatt.notification_reads",
            """
            CREATE TABLE vomatt.notification_reads (
                user_id UUID        NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
                vote_id UUID        NOT NULL REFERENCES vomatt.votes (id) ON DELETE CASCADE,
                read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (user_id, vote_id))""",
            "CREATE INDEX idx_notification_reads_vote ON vomatt.notification_reads (vote_id)");

    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    @DisplayName("should turn reads before a Poll's end into its Invitation and reads after into its Result, with the baseline's names, also on rerun")
    void shouldKeyReadsByNotificationWhenMigrationRuns() throws Exception {
        User owner = persistUser("mig-owner-" + System.nanoTime());
        User invitee = persistUser("mig-inv-" + System.nanoTime());
        User voter = persistUser("mig-voter-" + System.nanoTime());
        User removed = persistUser("mig-removed-" + System.nanoTime());
        Vote open = inviteOnly(owner, now.minusHours(1), now.plusDays(1));
        Invitee openInvite = new Invitee(open, invitee);
        em.persist(openInvite);
        Vote ended = inviteOnly(owner, now.minusDays(2), now.minusHours(1));
        Invitee endedInvite = new Invitee(ended, invitee);
        em.persist(endedInvite);
        em.persist(new UserVote(voter, ended, ended.getOptions().iterator().next()));
        em.flush();
        List<String> schemaNames = names();

        TO_POLL_READS.forEach(sql -> em.createNativeQuery(sql).executeUpdate());
        oldRead(invitee, open, now.minusMinutes(30));              // the Invitation, Poll still Open
        oldRead(invitee, ended, now.minusDays(1));                 // the Invitation, read before the Poll Ended
        oldRead(voter, ended, now.minusMinutes(10));               // the Ended Notification
        oldRead(removed, open, now.minusMinutes(30));              // an Invitation of someone no longer invited
        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n")).strip();
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertThat(names()).isEqualTo(schemaNames);
        assertThat(reads()).containsExactlyInAnyOrder(
                invitee.getId() + " INVITATION " + openInvite.getId(),
                invitee.getId() + " INVITATION " + endedInvite.getId(),
                voter.getId() + " RESULT " + ended.getId());
        PollNotifications notifications = new PollNotifications(new NotificationRepository(em));
        assertThat(notifications.countUnread(invitee.getId(), now)).isEqualTo(1); // the Result of the Poll that Ended since
        assertThat(notifications.countUnread(voter.getId(), now)).isZero();
    }

    private Vote inviteOnly(User owner, OffsetDateTime start, OffsetDateTime end) {
        Vote poll = persistPoll(owner, start, end, "A", "B");
        poll.setAudience(Audience.INVITE_ONLY);
        return poll;
    }

    private void oldRead(User user, Vote poll, OffsetDateTime readAt) {
        em.createNativeQuery("INSERT INTO vomatt.notification_reads (user_id, vote_id, read_at) VALUES (:userId, :voteId, :readAt)")
                .setParameter("userId", user.getId()).setParameter("voteId", poll.getId()).setParameter("readAt", readAt)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<String> reads() {
        return em.createNativeQuery("SELECT CAST(user_id AS text) || ' ' || kind || ' ' || CAST(source_id AS text) FROM vomatt.notification_reads")
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> names() {
        return em.createNativeQuery("""
                SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = CAST('vomatt.notification_reads' AS regclass)
                UNION ALL SELECT indexname FROM pg_indexes WHERE schemaname = 'vomatt' AND tablename = 'notification_reads'
                UNION ALL SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns
                          WHERE table_schema = 'vomatt' AND table_name = 'notification_reads'
                ORDER BY 1""").getResultList();
    }
}

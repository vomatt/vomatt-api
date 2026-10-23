package com.vomatt.common.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.vomatt.common.audit.AuditLog;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.repository.PostgresRepositoryTest;

@Import(RetentionJob.class)
@DisplayName("RetentionJob (Postgres)")
class RetentionPostgresTest extends PostgresRepositoryTest {

    @Autowired RetentionJob retentionJob;

    @Test
    @DisplayName("should remove expired rows and seal anonymous Ballots of Ended Polls, leaving everything else")
    void shouldApplyRetentionWhenRun() {
        OffsetDateTime now = OffsetDateTime.now();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User owner = persistUser("ret-owner-" + tag);
        User anon = persistUser("ret-anon-" + tag);
        User named = persistUser("ret-named-" + tag);
        User member = persistUser("ret-member-" + tag);
        Vote ended = persistPoll(owner, now.minusDays(60), now.minusDays(40), "A", "B");
        Vote open = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote recentlyEnded = persistPoll(owner, now.minusDays(3), now.minusDays(1), "A", "B");

        // Ballots: anonymous on Ended and Open Polls, named on the Ended Poll
        UserVote sealed = ballot(anon, ended, true);
        UUID sealedOption = sealed.getOption().getId();
        UserVote keptOpen = ballot(anon, open, true);
        UserVote keptNamed = ballot(named, ended, false);
        // Invitees: unregistered on the old Ended Poll (goes); on the recently Ended and Open Polls, and registered (stay)
        Invitee gone = new Invitee(ended, "gone-" + tag + "@example.com");
        Invitee recent = new Invitee(recentlyEnded, "recent-" + tag + "@example.com");
        Invitee pending = new Invitee(open, "pending-" + tag + "@example.com");
        Invitee registered = new Invitee(ended, member);
        for (Invitee i : new Invitee[] { gone, recent, pending, registered }) {
            em.persist(i);
        }
        // Audit logs: one old, one fresh
        AuditLog old = new AuditLog(owner.getId().toString(), "owner", "CREATE", "VOTE", ended.getId().toString());
        AuditLog fresh = new AuditLog(owner.getId().toString(), "owner", "CREATE", "VOTE", open.getId().toString());
        em.persist(old);
        em.persist(fresh);
        em.flush();
        em.createNativeQuery("UPDATE vomatt.audit_logs SET created_at = :at WHERE id = :id")
                .setParameter("at", now.minusDays(181)).setParameter("id", old.getId()).executeUpdate();
        // Invitation email records: one old, one fresh
        insertInvitationEmail(ended, "old-" + tag + "@example.com", now.minusDays(8));
        insertInvitationEmail(open, "new-" + tag + "@example.com", now.minusDays(1));
        em.clear();

        RetentionJob.Result first = retentionJob.run(now);
        RetentionJob.Result second = retentionJob.run(now);

        assertThat(first.auditLogs()).isGreaterThanOrEqualTo(1);
        assertThat(first.invitationEmails()).isGreaterThanOrEqualTo(1);
        assertThat(first.unregisteredInvitees()).isGreaterThanOrEqualTo(1);
        assertThat(first.sealedBallots()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM vomatt.audit_logs WHERE id = :p", old.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM vomatt.audit_logs WHERE id = :p", fresh.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM vomatt.vote_invitation_emails WHERE email LIKE :p", "%-" + tag + "@example.com")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM vomatt.vote_invitees WHERE id = :p", gone.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM vomatt.vote_invitees WHERE id = :p", recent.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM vomatt.vote_invitees WHERE id = :p", pending.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM vomatt.vote_invitees WHERE id = :p", registered.getId())).isEqualTo(1);
        assertThat(optionOf(sealed)).isNull();
        assertThat(optionOf(keptOpen)).isNotNull();
        assertThat(optionOf(keptNamed)).isNotNull();
        // Idempotent, and option totals are untouched by sealing (both Ballots on the Ended Poll chose this option)
        assertThat(second.sealedBallots()).isZero();
        assertThat(count("SELECT vote_count FROM vomatt.vote_options WHERE id = :p", sealedOption)).isEqualTo(2);
    }

    private UserVote ballot(User user, Vote poll, boolean anonymous) {
        UserVote ballot = new UserVote(user, poll, poll.getOptions().iterator().next());
        ballot.setAnonymous(anonymous);
        em.persist(ballot);
        em.createNativeQuery("UPDATE vomatt.vote_options SET vote_count = vote_count + 1 WHERE id = :id")
                .setParameter("id", ballot.getOption().getId()).executeUpdate();
        return ballot;
    }

    private void insertInvitationEmail(Vote poll, String email, OffsetDateTime sentAt) {
        em.createNativeQuery("INSERT INTO vomatt.vote_invitation_emails (vote_id, email, sent_at) VALUES (:v, :e, :at)")
                .setParameter("v", poll.getId()).setParameter("e", email).setParameter("at", sentAt).executeUpdate();
    }

    private Object optionOf(UserVote ballot) {
        return em.createNativeQuery("SELECT option_id FROM vomatt.user_votes WHERE id = :id")
                .setParameter("id", ballot.getId()).getSingleResult();
    }

    private long count(String sql, Object param) {
        return ((Number) em.createNativeQuery(sql).setParameter("p", param).getSingleResult()).longValue();
    }
}

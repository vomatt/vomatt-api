package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.vomatt.entity.User;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;

/** Runs the real db/reconcile-vote-counts.sql inside the rolled-back test transaction. */
@DisplayName("reconcile-vote-counts.sql (Postgres)")
class ReconcileVoteCountsPostgresTest extends PostgresRepositoryTest {

    @Test
    @DisplayName("should restore option counts and Turnout to the actual Ballot rows when they drifted")
    void shouldFixDriftedCountsWhenReconcileRuns() {
        User owner = persistUser("reconcile-owner-" + System.nanoTime());
        User voter = persistUser("reconcile-voter-" + System.nanoTime());
        OffsetDateTime now = OffsetDateTime.now();
        Vote voted = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote empty = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        VoteOption chosen = voted.getOptions().iterator().next();
        em.persist(new UserVote(voter, voted, chosen));
        em.flush();
        em.createNativeQuery("UPDATE vomatt.votes SET turnout = 7 WHERE id IN (:ids)")
                .setParameter("ids", java.util.List.of(voted.getId(), empty.getId())).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.vote_options SET vote_count = 5 WHERE id = :id")
                .setParameter("id", chosen.getId()).executeUpdate();

        em.unwrap(Session.class).doWork(connection ->
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/reconcile-vote-counts.sql")));

        assertThat(turnout(voted)).isEqualTo(1);
        assertThat(turnout(empty)).isZero();
        assertThat(((Number) em.createNativeQuery("SELECT vote_count FROM vomatt.vote_options WHERE id = :id")
                .setParameter("id", chosen.getId()).getSingleResult()).intValue()).isEqualTo(1);
    }

    private int turnout(Vote vote) {
        return ((Number) em.createNativeQuery("SELECT turnout FROM vomatt.votes WHERE id = :id")
                .setParameter("id", vote.getId()).getSingleResult()).intValue();
    }

    @Test
    @DisplayName("should restore share counts and tag usage to the actual rows when they drifted")
    void shouldFixDriftedSharesAndTagUsageWhenReconcileRuns() {
        User owner = persistUser("reconcile-sharer-" + System.nanoTime());
        OffsetDateTime now = OffsetDateTime.now();
        Vote shared = persistPoll(owner, now.minusHours(1), now.plusDays(1), "A", "B");
        Vote unlisted = persistPoll(owner, now.minusHours(1), now.plusDays(1), com.vomatt.entity.Audience.UNLISTED, "A", "B");
        com.vomatt.entity.Tag tag = persistTag();
        com.vomatt.entity.Tag unused = persistTag();
        shared.addTag(tag);
        unlisted.addTag(tag);
        em.flush();
        em.createNativeQuery("INSERT INTO vomatt.vote_shares (vote_id, user_id, channel) VALUES (:vote, :user, 'COPY_LINK')")
                .setParameter("vote", shared.getId()).setParameter("user", owner.getId()).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.votes SET share_count = 4 WHERE id IN (:ids)")
                .setParameter("ids", java.util.List.of(shared.getId(), unlisted.getId())).executeUpdate();
        em.createNativeQuery("UPDATE vomatt.tags SET usage_count = 5 WHERE id IN (:ids)")
                .setParameter("ids", java.util.List.of(tag.getId(), unused.getId())).executeUpdate();

        em.unwrap(Session.class).doWork(connection ->
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/reconcile-vote-counts.sql")));

        assertThat(intColumn("SELECT share_count FROM vomatt.votes WHERE id = :id", shared.getId())).isEqualTo(1);
        assertThat(intColumn("SELECT share_count FROM vomatt.votes WHERE id = :id", unlisted.getId())).isZero();
        assertThat(intColumn("SELECT usage_count FROM vomatt.tags WHERE id = :id", tag.getId())).isEqualTo(1);
        assertThat(intColumn("SELECT usage_count FROM vomatt.tags WHERE id = :id", unused.getId())).isZero();
    }

    private int intColumn(String sql, java.util.UUID id) {
        return ((Number) em.createNativeQuery(sql).setParameter("id", id).getSingleResult()).intValue();
    }
}

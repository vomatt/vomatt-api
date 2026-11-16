package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The RetentionJob and admin queries can be served by an index instead of a whole-table scan. With sequential scans
 * off, the plan names the index only when its columns and predicate match the query.
 */
@DisplayName("Job and admin query indexes (Postgres)")
class JobAdminIndexesPostgresTest extends PostgresRepositoryTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void disableSeqScan() {
        em.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
    }

    @Test
    void shouldUsePartialIndexWhenSealingAnonymousBallots() throws Exception {
        String sql = UserVoteRepository.class.getMethod("sealAnonymousBallotsOfPollsEndedBy", OffsetDateTime.class)
                .getAnnotation(Query.class).value();

        assertThat(explain(sql.replace(":now", "now()"))).contains("idx_user_votes_unsealed_anonymous");
    }

    @Test
    void shouldUsePartialIndexWhenFindingProPollsWithoutReport() {
        // Same SQL as PollReportRepository.findEndedProPollsWithoutReport
        assertThat(explain("""
                SELECT v.id FROM vomatt.votes v
                WHERE v.pro AND v.end_time <= now() AND v.deleted_at IS NULL AND v.end_time > v.start_time
                  AND NOT EXISTS (SELECT 1 FROM vomatt.poll_reports r WHERE r.vote_id = v.id)"""))
                .contains("idx_votes_pro_end");
    }

    @Test
    void shouldUseCreatedIndexesWhenPagingAdminLists() {
        // Unfiltered first pages of AdminQueryRepository.findUsers / findVotes / findAuditLogs(action)
        assertThat(explain("SELECT u.* FROM vomatt.users u WHERE true ORDER BY u.created_at DESC, u.id DESC LIMIT 20"))
                .contains("idx_users_created_id");
        assertThat(explain("SELECT v.* FROM vomatt.votes v WHERE true ORDER BY v.created_at DESC, v.id DESC LIMIT 20"))
                .contains("idx_votes_created_id");
        assertThat(explain("SELECT a.* FROM vomatt.audit_logs a WHERE true AND a.action = 'DELETE_USER'"
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT 20"))
                .contains("idx_audit_logs_action_created");
    }

    @Test
    void shouldUseCreatedIndexWhenCountingRecentBallots() {
        assertThat(explain("SELECT count(*) FROM vomatt.user_votes WHERE created_at >= now() - interval '1 day'"))
                .contains("idx_user_votes_created");
    }

    private String explain(String sql) {
        return em.createNativeQuery("EXPLAIN " + sql).getResultList().toString();
    }
}

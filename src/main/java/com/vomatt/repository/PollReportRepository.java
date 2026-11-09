package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Stored Pro poll report snapshots ({@code poll_reports}) and the Ballot rows they are computed from. */
@Repository
@RequiredArgsConstructor
public class PollReportRepository {

    private final EntityManager em;

    /** The stored snapshot as JSON text. */
    public Optional<String> findData(UUID voteId) {
        List<?> rows = em.createNativeQuery("SELECT CAST(data AS text) FROM vomatt.poll_reports WHERE vote_id = :voteId")
                .setParameter("voteId", voteId).getResultList();
        return rows.stream().findFirst().map(String.class::cast);
    }

    /**
     * Stores the snapshot unless one exists. A concurrent writer waits on the primary key until the first commits,
     * then inserts nothing, so a Poll never gets two rows. Returns whether this call stored it.
     */
    public boolean insertIfAbsent(UUID voteId, OffsetDateTime generatedAt, String data) {
        return em.createNativeQuery("""
                INSERT INTO vomatt.poll_reports (vote_id, generated_at, data) VALUES (:voteId, :at, CAST(:data AS jsonb))
                ON CONFLICT (vote_id) DO NOTHING""")
                .setParameter("voteId", voteId).setParameter("at", generatedAt).setParameter("data", data)
                .executeUpdate() == 1;
    }

    /** Ended, not Deleted Pro polls that have no snapshot yet. */
    @SuppressWarnings("unchecked")
    public List<UUID> findEndedProPollsWithoutReport(OffsetDateTime now) {
        return em.createNativeQuery("""
                SELECT v.id FROM vomatt.votes v
                WHERE v.pro AND v.end_time <= :now AND v.deleted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM vomatt.poll_reports r WHERE r.vote_id = v.id)""", UUID.class)
                .setParameter("now", now).getResultList();
    }

    public List<BallotDemographics> findBallots(UUID voteId) {
        return em.createQuery("""
                SELECT new com.vomatt.repository.BallotDemographics(o.id, u.birthday, u.gender)
                FROM UserVote uv JOIN uv.user u LEFT JOIN uv.option o
                WHERE uv.vote.id = :voteId""", BallotDemographics.class)
                .setParameter("voteId", voteId).getResultList();
    }
}

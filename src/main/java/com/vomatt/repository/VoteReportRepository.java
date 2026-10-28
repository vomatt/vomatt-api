package com.vomatt.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vomatt.entity.VoteReport;

@Repository
public interface VoteReportRepository extends JpaRepository<VoteReport, UUID> {

    /**
     * Idempotent, also under concurrent requests: a user's later reports of the same Poll are dropped. Returns 1 when
     * the report is new, 0 when it already existed or the Poll is gone or Deleted.
     * The Poll row is share-locked, so a delete still in flight is waited for.
     */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.vote_reports (id, vote_id, reporter_id, reason, note)
            SELECT :id, :voteId, :reporterId, :reason, CAST(:note AS varchar)
            WHERE EXISTS (SELECT 1 FROM vomatt.votes WHERE id = :voteId AND deleted_at IS NULL FOR SHARE)
            ON CONFLICT (vote_id, reporter_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("voteId") UUID voteId, @Param("reporterId") UUID reporterId,
                       @Param("reason") String reason, @Param("note") String note);

    /** Back-office list, newest first, with the Poll, its owner and the reporter. */
    @EntityGraph(attributePaths = { "vote", "vote.creator", "reporter" })
    @Query("SELECT r FROM VoteReport r ORDER BY r.createdAt DESC, r.id DESC")
    Page<VoteReport> findPage(Pageable pageable);
}

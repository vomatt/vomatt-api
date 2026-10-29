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

import com.vomatt.entity.CommentReport;

@Repository
public interface CommentReportRepository extends JpaRepository<CommentReport, UUID> {

    /**
     * Idempotent, also under concurrent requests: a user's later reports of the same Comment are dropped. Returns 1
     * when the report is new, 0 when it already existed, the reporter does not exist or the Comment is gone or deleted.
     * The reporter and Comment rows are locked, user first as in account deletion, so an account deletion or Comment
     * delete still in flight is waited for.
     */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.comment_reports (id, comment_id, reporter_id, reason, note)
            SELECT :id, :commentId, :reporterId, :reason, CAST(:note AS varchar)
            WHERE EXISTS (SELECT 1 FROM vomatt.users WHERE id = :reporterId FOR KEY SHARE)
              AND EXISTS (SELECT 1 FROM vomatt.vote_comments WHERE id = :commentId AND NOT is_deleted FOR SHARE)
            ON CONFLICT (comment_id, reporter_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("commentId") UUID commentId, @Param("reporterId") UUID reporterId,
                       @Param("reason") String reason, @Param("note") String note);

    /** The report exists and its Comment is not deleted. */
    boolean existsByCommentIdAndReporterIdAndCommentIsDeletedFalse(UUID commentId, UUID reporterId);

    /** Back-office list, newest first, with the Comment, its author and the reporter. */
    @EntityGraph(attributePaths = { "comment", "comment.user", "reporter" })
    @Query("SELECT r FROM CommentReport r ORDER BY r.createdAt DESC, r.id DESC")
    Page<CommentReport> findPage(Pageable pageable);
}

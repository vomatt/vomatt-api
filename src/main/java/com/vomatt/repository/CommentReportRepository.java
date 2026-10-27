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

    /** Idempotent, also under concurrent requests: a user's later reports of the same Comment are dropped. */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.comment_reports (id, comment_id, reporter_id, reason, note)
            VALUES (:id, :commentId, :reporterId, :reason, :note)
            ON CONFLICT (comment_id, reporter_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("commentId") UUID commentId, @Param("reporterId") UUID reporterId,
                       @Param("reason") String reason, @Param("note") String note);

    /** Back-office list, newest first, with the Comment, its author and the reporter. */
    @EntityGraph(attributePaths = { "comment", "comment.user", "reporter" })
    @Query("SELECT r FROM CommentReport r ORDER BY r.createdAt DESC, r.id DESC")
    Page<CommentReport> findPage(Pageable pageable);
}

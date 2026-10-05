package com.vomatt.repository;

import com.vomatt.entity.VoteComment;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoteCommentRepository extends JpaRepository<VoteComment, UUID> {

    /**
     * Find all comments for a vote
     */
    @Query("SELECT c FROM VoteComment c WHERE c.vote.id = :voteId AND c.isDeleted = false")
    Page<VoteComment> findByVoteId(@Param("voteId") UUID voteId, Pageable pageable);

    /**
     * Find comment by ID, excluding deleted comments
     */
    @Query("SELECT c FROM VoteComment c WHERE c.id = :id AND c.isDeleted = false")
    Optional<VoteComment> findByIdAndNotDeleted(@Param("id") UUID id);

    /**
     * Count comments for a vote
     */
    @Query("SELECT COUNT(c) FROM VoteComment c WHERE c.vote.id = :voteId AND c.isDeleted = false")
    long countByVoteId(@Param("voteId") UUID voteId);

    /** Visible message counts (Comments and Replies, not deleted) for a page of Polls. */
    @Query("SELECT c.vote.id AS id, COUNT(c) AS count FROM VoteComment c "
            + "WHERE c.vote.id IN :voteIds AND c.isDeleted = false GROUP BY c.vote.id")
    List<IdCount> countVisibleByVoteIds(@Param("voteIds") Collection<UUID> voteIds);

    /**
     * Keyset page of a Poll's top-level Comments, newest first; a deleted Comment stays as a placeholder
     * while it still has visible Replies. Null cursor values for the first page.
     */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.vote.id = :voteId AND c.parent IS NULL
              AND (c.isDeleted = false
                   OR EXISTS (SELECT 1 FROM VoteComment r WHERE r.parent = c AND r.isDeleted = false))
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR c.createdAt < :afterTime
                   OR (c.createdAt = :afterTime AND c.id < :afterId))
            ORDER BY c.createdAt DESC, c.id DESC""")
    List<VoteComment> findPageByVoteId(@Param("voteId") UUID voteId, @Param("afterTime") OffsetDateTime afterTime,
                                       @Param("afterId") UUID afterId, Limit limit);

    /** Keyset page of a Comment's visible Replies, oldest first. */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.parent.id = :parentId AND c.isDeleted = false
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR c.createdAt > :afterTime
                   OR (c.createdAt = :afterTime AND c.id > :afterId))
            ORDER BY c.createdAt, c.id""")
    List<VoteComment> findReplyPage(@Param("parentId") UUID parentId, @Param("afterTime") OffsetDateTime afterTime,
                                    @Param("afterId") UUID afterId, Limit limit);

    /** Visible Reply counts for a page of top-level Comments. */
    @Query("SELECT c.parent.id AS id, COUNT(c) AS count FROM VoteComment c "
            + "WHERE c.parent.id IN :parentIds AND c.isDeleted = false GROUP BY c.parent.id")
    List<IdCount> countRepliesByParentIds(@Param("parentIds") Collection<UUID> parentIds);

    /**
     * Find all comments by user
     */
    @Query("SELECT c FROM VoteComment c WHERE c.user.id = :userId AND c.isDeleted = false")
    List<VoteComment> findByUserId(@Param("userId") UUID userId);
}

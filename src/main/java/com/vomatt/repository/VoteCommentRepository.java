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

    /** Keyset page of a Poll's visible comments, newest first; null cursor values for the first page. */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.vote.id = :voteId AND c.isDeleted = false
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR c.createdAt < :afterTime
                   OR (c.createdAt = :afterTime AND c.id < :afterId))
            ORDER BY c.createdAt DESC, c.id DESC""")
    List<VoteComment> findPageByVoteId(@Param("voteId") UUID voteId, @Param("afterTime") OffsetDateTime afterTime,
                                       @Param("afterId") UUID afterId, Limit limit);

    /**
     * Find all comments by user
     */
    @Query("SELECT c FROM VoteComment c WHERE c.user.id = :userId AND c.isDeleted = false")
    List<VoteComment> findByUserId(@Param("userId") UUID userId);
}

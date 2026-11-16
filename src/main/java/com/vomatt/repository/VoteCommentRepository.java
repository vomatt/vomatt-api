package com.vomatt.repository;

import com.vomatt.entity.VoteComment;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Find comment by ID, excluding deleted comments
     */
    @Query("SELECT c FROM VoteComment c WHERE c.id = :id AND c.isDeleted = false")
    Optional<VoteComment> findByIdAndNotDeleted(@Param("id") UUID id);

    /** Atomic +1 on the Poll's stored comment count (votes.comment_count); call once per inserted Comment or Reply. */
    @Modifying
    @Query(value = "UPDATE vomatt.votes SET comment_count = comment_count + 1 WHERE id = :voteId", nativeQuery = true)
    void incrementCommentCount(@Param("voteId") UUID voteId);

    /**
     * Soft deletes the Comment and takes one off its Poll's comment count in one statement; 1 when it was live, 0 when
     * it was already deleted. Do not mark the managed entity deleted first: the auto-flush before this statement would
     * leave nothing for it to match.
     */
    @Modifying
    @Query(value = """
            WITH d AS (
                UPDATE vomatt.vote_comments SET is_deleted = TRUE, updated_at = now()
                WHERE id = :id AND NOT is_deleted RETURNING vote_id)
            UPDATE vomatt.votes SET comment_count = comment_count - 1 WHERE id IN (SELECT vote_id FROM d)""",
            nativeQuery = true)
    int softDelete(@Param("id") UUID id);

    /**
     * Releases from the stored comment counts the live Comments that cascade with the user: their own and every Reply
     * under their top-level Comments (deleted or not). Run before deleting the user.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE vomatt.votes v SET comment_count = v.comment_count - g.n
            FROM (SELECT c.vote_id, COUNT(*) AS n
                  FROM vomatt.vote_comments c LEFT JOIN vomatt.vote_comments p ON p.id = c.parent_id
                  WHERE NOT c.is_deleted AND (c.user_id = :userId OR p.user_id = :userId)
                  GROUP BY c.vote_id) g
            WHERE v.id = g.vote_id""", nativeQuery = true)
    void decrementCommentCountForUserComments(@Param("userId") UUID userId);

    /**
     * Keyset page of a Poll's top-level Comments, newest first; a deleted Comment stays as a placeholder
     * while it still has visible Replies. Threads started by a user the viewer blocked are left out, and so are their
     * Replies ({@code viewerId} null: nothing is left out). Null cursor values for the first page.
     */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.vote.id = :voteId AND c.parent IS NULL
              AND NOT EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = :viewerId AND b.blocked = c.user)
              AND (c.isDeleted = false
                   OR EXISTS (SELECT 1 FROM VoteComment r WHERE r.parent = c AND r.isDeleted = false
                              AND NOT EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = :viewerId AND b.blocked = r.user)))
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR c.createdAt < :afterTime
                   OR (c.createdAt = :afterTime AND c.id < :afterId))
            ORDER BY c.createdAt DESC, c.id DESC""")
    List<VoteComment> findPageByVoteId(@Param("voteId") UUID voteId, @Param("afterTime") OffsetDateTime afterTime,
                                       @Param("afterId") UUID afterId, @Param("viewerId") UUID viewerId,
                                       Limit limit);

    /** Keyset page of a Comment's visible Replies, oldest first, without those of users the viewer blocked. */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.parent.id = :parentId AND c.isDeleted = false
              AND NOT EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = :viewerId AND b.blocked = c.user)
              AND (CAST(:afterTime AS OffsetDateTime) IS NULL OR c.createdAt > :afterTime
                   OR (c.createdAt = :afterTime AND c.id > :afterId))
            ORDER BY c.createdAt, c.id""")
    List<VoteComment> findReplyPage(@Param("parentId") UUID parentId, @Param("afterTime") OffsetDateTime afterTime,
                                    @Param("afterId") UUID afterId, @Param("viewerId") UUID viewerId, Limit limit);

    /**
     * The first {@code perParent} Replies under each of a page of top-level Comments, filtered like
     * {@link #findReplyPage}, oldest first, in one statement.
     */
    @Query("""
            SELECT c FROM VoteComment c JOIN FETCH c.user
            WHERE c.id IN (SELECT p.id FROM (SELECT r.id AS id,
                                                    ROW_NUMBER() OVER (PARTITION BY r.parent.id ORDER BY r.createdAt, r.id) AS rn
                                             FROM VoteComment r
                                             WHERE r.parent.id IN :parentIds AND r.isDeleted = false
                                               AND NOT EXISTS (SELECT 1 FROM UserBlock b
                                                               WHERE b.blocker.id = :viewerId AND b.blocked = r.user)) p
                           WHERE p.rn <= :perParent)
            ORDER BY c.createdAt, c.id""")
    List<VoteComment> findReplyPreviews(@Param("parentIds") Collection<UUID> parentIds, @Param("viewerId") UUID viewerId,
                                        @Param("perParent") int perParent);

    /** Visible Reply counts for a page of top-level Comments, without Replies of users the viewer blocked. */
    @Query("SELECT c.parent.id AS id, COUNT(c) AS count FROM VoteComment c "
            + "WHERE c.parent.id IN :parentIds AND c.isDeleted = false AND NOT EXISTS (SELECT 1 FROM UserBlock b WHERE b.blocker.id = :viewerId AND b.blocked = c.user) GROUP BY c.parent.id")
    List<IdCount> countRepliesByParentIds(@Param("parentIds") Collection<UUID> parentIds,
                                          @Param("viewerId") UUID viewerId);

    /**
     * Whether the lists hide the Comment from the viewer: the viewer blocked its author or, for a Reply, the author of
     * its thread. One-way: a block by the author does not hide it.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vomatt.vote_comments c
                           LEFT JOIN vomatt.vote_comments p ON p.id = c.parent_id
                           JOIN vomatt.user_blocks b ON b.blocker_id = :viewerId AND b.blocked_id IN (c.user_id, p.user_id)
                           WHERE c.id = :commentId)""", nativeQuery = true)
    boolean isHiddenFrom(@Param("commentId") UUID commentId, @Param("viewerId") UUID viewerId);

    long countByUserId(UUID userId);

    /**
     * Find all comments by user
     */
    @Query("SELECT c FROM VoteComment c WHERE c.user.id = :userId AND c.isDeleted = false")
    List<VoteComment> findByUserId(@Param("userId") UUID userId);
}

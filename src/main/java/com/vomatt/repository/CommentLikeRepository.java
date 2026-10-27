package com.vomatt.repository;

import com.vomatt.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CommentLikeRepository extends JpaRepository<CommentLike, UUID> {

    /**
     * Idempotent, also under concurrent requests; returns 1 when the like is new, 0 when it already existed or the
     * user does not exist or the Comment is gone or deleted. The Comment row is share-locked, so a delete still in
     * flight is waited for and no like is left on a Comment deleted meanwhile.
     */
    @Modifying
    @Query(value = """
            INSERT INTO vomatt.comment_likes (id, comment_id, user_id)
            SELECT :id, :commentId, :userId
            WHERE EXISTS (SELECT 1 FROM vomatt.users WHERE id = :userId)
              AND EXISTS (SELECT 1 FROM vomatt.vote_comments WHERE id = :commentId AND NOT is_deleted FOR SHARE)
            ON CONFLICT (comment_id, user_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("commentId") UUID commentId, @Param("userId") UUID userId);

    boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

    /** Like counts for a page of comments. */
    @Query("SELECT cl.comment.id AS id, COUNT(cl) AS count FROM CommentLike cl "
            + "WHERE cl.comment.id IN :commentIds GROUP BY cl.comment.id")
    List<IdCount> countByCommentIds(@Param("commentIds") Collection<UUID> commentIds);

    /** Which of a page of comments the user has liked. */
    @Query("SELECT cl.comment.id FROM CommentLike cl WHERE cl.user.id = :userId AND cl.comment.id IN :commentIds")
    List<UUID> findLikedCommentIds(@Param("userId") UUID userId, @Param("commentIds") Collection<UUID> commentIds);

    /** Idempotent, also under concurrent requests; returns 1 when the like was removed, 0 when there was none. */
    @Modifying
    @Query("DELETE FROM CommentLike cl WHERE cl.comment.id = :commentId AND cl.user.id = :userId")
    int deleteByCommentIdAndUserId(@Param("commentId") UUID commentId, @Param("userId") UUID userId);
}

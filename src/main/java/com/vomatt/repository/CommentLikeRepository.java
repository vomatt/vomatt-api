package com.vomatt.repository;

import com.vomatt.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentLikeRepository extends JpaRepository<CommentLike, UUID> {

    /**
     * Check if user has liked a comment
     */
    @Query("SELECT CASE WHEN COUNT(cl) > 0 THEN true ELSE false END FROM CommentLike cl WHERE cl.comment.id = :commentId AND cl.user.id = :userId")
    boolean existsByCommentIdAndUserId(@Param("commentId") UUID commentId, @Param("userId") UUID userId);

    /**
     * Find like by comment and user
     */
    @Query("SELECT cl FROM CommentLike cl WHERE cl.comment.id = :commentId AND cl.user.id = :userId")
    Optional<CommentLike> findByCommentIdAndUserId(@Param("commentId") UUID commentId, @Param("userId") UUID userId);

    /**
     * Count likes for a comment
     */
    @Query("SELECT COUNT(cl) FROM CommentLike cl WHERE cl.comment.id = :commentId")
    long countByCommentId(@Param("commentId") UUID commentId);

    /**
     * Count likes for several comments at once (list pages)
     */
    @Query("SELECT cl.comment.id AS commentId, COUNT(cl) AS count FROM CommentLike cl "
            + "WHERE cl.comment.id IN :commentIds GROUP BY cl.comment.id")
    List<CommentLikeCount> countByCommentIds(@Param("commentIds") Collection<UUID> commentIds);

    /**
     * Which of these comments the user has liked
     */
    @Query("SELECT cl.comment.id FROM CommentLike cl WHERE cl.user.id = :userId AND cl.comment.id IN :commentIds")
    List<UUID> findLikedCommentIds(@Param("userId") UUID userId, @Param("commentIds") Collection<UUID> commentIds);

    /**
     * Delete like by comment and user
     */
    void deleteByCommentIdAndUserId(UUID commentId, UUID userId);
}

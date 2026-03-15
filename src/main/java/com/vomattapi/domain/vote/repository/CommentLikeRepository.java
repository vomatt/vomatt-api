package com.vomattapi.domain.vote.repository;

import com.vomattapi.domain.vote.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Delete like by comment and user
     */
    void deleteByCommentIdAndUserId(UUID commentId, UUID userId);
}

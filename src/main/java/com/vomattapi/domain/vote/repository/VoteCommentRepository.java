package com.vomattapi.domain.vote.repository;

import com.vomattapi.domain.vote.VoteComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /**
     * Find all comments by user
     */
    @Query("SELECT c FROM VoteComment c WHERE c.user.id = :userId AND c.isDeleted = false")
    List<VoteComment> findByUserId(@Param("userId") UUID userId);
}

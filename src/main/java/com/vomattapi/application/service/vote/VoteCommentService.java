package com.vomattapi.application.service.vote;

import com.vomattapi.application.dto.vote.CreateCommentRequest;
import com.vomattapi.application.dto.vote.UpdateCommentRequest;
import com.vomattapi.application.dto.vote.CommentDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface VoteCommentService {

    /**
     * Create a new comment on a vote
     */
    CommentDto createComment(String voteId, String userId, CreateCommentRequest request);

    /**
     * Get all comments for a vote (paginated)
     */
    Page<CommentDto> getCommentsByVote(String voteId, Pageable pageable, String currentUserId);

    /**
     * Update a comment (only by the comment owner)
     */
    CommentDto updateComment(UUID commentId, String userId, UpdateCommentRequest request);

    /**
     * Delete a comment (soft delete, only by the comment owner)
     */
    void deleteComment(UUID commentId, String userId);

    /**
     * Get a single comment by ID
     */
    CommentDto getComment(UUID commentId, String currentUserId);

    /**
     * Count total comments for a vote
     */
    long countCommentsByVote(String voteId);

    /**
     * Like a comment
     */
    void likeComment(UUID commentId, String userId);

    /**
     * Unlike a comment
     */
    void unlikeComment(UUID commentId, String userId);
}

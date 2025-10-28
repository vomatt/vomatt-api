package com.vomattapi.application.service;

import com.vomattapi.application.dto.request.CreateCommentRequest;
import com.vomattapi.application.dto.request.UpdateCommentRequest;
import com.vomattapi.application.dto.response.CommentDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VoteCommentService {

    /**
     * Create a new comment on a vote
     */
    CommentDto createComment(String voteId, String userId, CreateCommentRequest request);

    /**
     * Get all comments for a vote (paginated)
     */
    Page<CommentDto> getCommentsByVote(String voteId, Pageable pageable);

    /**
     * Update a comment (only by the comment owner)
     */
    CommentDto updateComment(Long commentId, String userId, UpdateCommentRequest request);

    /**
     * Delete a comment (soft delete, only by the comment owner)
     */
    void deleteComment(Long commentId, String userId);

    /**
     * Get a single comment by ID
     */
    CommentDto getComment(Long commentId);

    /**
     * Count total comments for a vote
     */
    long countCommentsByVote(String voteId);
}

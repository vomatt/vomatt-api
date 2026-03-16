package com.vomattapi.application.service.impl;

import com.vomattapi.application.dto.request.CreateCommentRequest;
import com.vomattapi.application.dto.request.UpdateCommentRequest;
import com.vomattapi.application.dto.response.CommentDto;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.mapper.CommentMapper;
import com.vomattapi.application.service.VoteCommentService;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.CommentLike;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteComment;
import com.vomattapi.domain.vote.repository.CommentLikeRepository;
import com.vomattapi.domain.vote.repository.VoteCommentRepository;
import com.vomattapi.domain.vote.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class VoteCommentServiceImpl implements VoteCommentService {

    private final VoteCommentRepository commentRepository;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentMapper commentMapper;

    @Override
    public CommentDto createComment(String voteId, String userId, CreateCommentRequest request) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> new EntityNotFoundException("User", userId));

        VoteComment comment = new VoteComment(vote, user, request.getText());
        comment = commentRepository.save(comment);

        log.info("Comment created: {} on vote: {} by user: {}", comment.getId(), voteId, userId);

        return convertToCommentDto(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentDto> getCommentsByVote(String voteId, Pageable pageable, String currentUserId) {
        if (!voteRepository.existsById(UUID.fromString(voteId))) {
            throw new VoteNotFoundException(voteId);
        }

        Page<VoteComment> comments = commentRepository.findByVoteId(UUID.fromString(voteId), pageable);
        return comments.map(comment -> convertToCommentDto(comment, currentUserId));
    }

    @Override
    public CommentDto updateComment(UUID commentId, String userId, UpdateCommentRequest request) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        if (!comment.canBeEditedBy(userId)) {
            throw new UnauthorizedOperationException("update", "comment");
        }

        comment.updateContent(request.getText());
        comment = commentRepository.save(comment);

        log.info("Comment {} updated by user {}", commentId, userId);

        return convertToCommentDto(comment);
    }

    @Override
    public void deleteComment(UUID commentId, String userId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        if (!comment.canBeDeletedBy(userId)) {
            throw new UnauthorizedOperationException("delete", "comment");
        }

        comment.softDelete();
        commentRepository.save(comment);

        log.info("Comment {} deleted by user {}", commentId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public CommentDto getComment(UUID commentId, String currentUserId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        return convertToCommentDto(comment, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countCommentsByVote(String voteId) {
        if (!voteRepository.existsById(UUID.fromString(voteId))) {
            throw new VoteNotFoundException(voteId);
        }

        return commentRepository.countByVoteId(UUID.fromString(voteId));
    }

    @Override
    public void likeComment(UUID commentId, String userId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> new EntityNotFoundException("User", userId));

        UUID userUuid = UUID.fromString(userId);
        if (commentLikeRepository.existsByCommentIdAndUserId(commentId, userUuid)) {
            log.warn("User {} already liked comment {}", userId, commentId);
            return;
        }

        CommentLike like = new CommentLike(comment, user);
        commentLikeRepository.save(like);

        log.info("User {} liked comment {}", userId, commentId);
    }

    @Override
    public void unlikeComment(UUID commentId, String userId) {
        commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        commentLikeRepository.deleteByCommentIdAndUserId(commentId, UUID.fromString(userId));

        log.info("User {} unliked comment {}", userId, commentId);
    }

    private CommentDto convertToCommentDto(VoteComment comment) {
        return convertToCommentDto(comment, null);
    }

    private CommentDto convertToCommentDto(VoteComment comment, String currentUserId) {
        long likeCount = commentLikeRepository.countByCommentId(comment.getId());
        boolean isLiked = currentUserId != null &&
            commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), UUID.fromString(currentUserId));
        return commentMapper.toDto(comment, likeCount, isLiked);
    }
}

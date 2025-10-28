package com.vomattapi.application.service.impl;

import com.vomattapi.application.dto.request.CreateCommentRequest;
import com.vomattapi.application.dto.request.UpdateCommentRequest;
import com.vomattapi.application.dto.response.CommentDto;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.service.VoteCommentService;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteComment;
import com.vomattapi.domain.vote.repository.VoteCommentRepository;
import com.vomattapi.domain.vote.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class VoteCommentServiceImpl implements VoteCommentService {

    private final VoteCommentRepository commentRepository;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;

    @Override
    public CommentDto createComment(String voteId, String userId, CreateCommentRequest request) {
        Vote vote = voteRepository.findById(voteId)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User", userId));

        VoteComment comment = new VoteComment(vote, user, request.getContent());
        comment = commentRepository.save(comment);

        log.info("Comment created: {} on vote: {} by user: {}", comment.getId(), voteId, userId);

        return convertToCommentDto(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentDto> getCommentsByVote(String voteId, Pageable pageable) {
        if (!voteRepository.existsById(voteId)) {
            throw new VoteNotFoundException(voteId);
        }

        Page<VoteComment> comments = commentRepository.findByVoteId(voteId, pageable);
        return comments.map(this::convertToCommentDto);
    }

    @Override
    public CommentDto updateComment(Long commentId, String userId, UpdateCommentRequest request) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        if (!comment.canBeEditedBy(userId)) {
            throw new UnauthorizedOperationException("update", "comment");
        }

        comment.updateContent(request.getContent());
        comment = commentRepository.save(comment);

        log.info("Comment {} updated by user {}", commentId, userId);

        return convertToCommentDto(comment);
    }

    @Override
    public void deleteComment(Long commentId, String userId) {
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
    public CommentDto getComment(Long commentId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> new EntityNotFoundException("Comment", commentId.toString()));

        return convertToCommentDto(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public long countCommentsByVote(String voteId) {
        if (!voteRepository.existsById(voteId)) {
            throw new VoteNotFoundException(voteId);
        }

        return commentRepository.countByVoteId(voteId);
    }

    private CommentDto convertToCommentDto(VoteComment comment) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId());
        dto.setVoteId(comment.getVote().getId());
        dto.setUserId(comment.getUser().getId());
        dto.setUsername(comment.getUser().getUsername());
        dto.setContent(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setUpdatedAt(comment.getUpdatedAt());
        dto.setEdited(!comment.getCreatedAt().equals(comment.getUpdatedAt()));
        return dto;
    }
}

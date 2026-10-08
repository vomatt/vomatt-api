package com.vomatt.comments;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.CommentMapper;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import com.vomatt.entity.CommentLike;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
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
public class VoteCommentService {

    private final VoteCommentRepository commentRepository;
    private final VoteRepository voteRepository;
    private final UserRepository userRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentMapper commentMapper;

    public CommentDto createComment(String voteId, String userId, CreateCommentRequest request) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        VoteComment comment = new VoteComment(vote, user, request.getText());
        // created_at / updated_at are filled on flush; the response needs them now
        comment = commentRepository.saveAndFlush(comment);

        log.info("Comment created: {} on vote: {} by user: {}", comment.getId(), voteId, userId);

        return convertToCommentDto(comment);
    }

    @Transactional(readOnly = true)
    public Page<CommentDto> getCommentsByVote(String voteId, Pageable pageable, String currentUserId) {
        if (!voteRepository.existsById(UUID.fromString(voteId))) {
            throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);
        }

        Page<VoteComment> comments = commentRepository.findByVoteId(UUID.fromString(voteId), pageable);
        return comments.map(comment -> convertToCommentDto(comment, currentUserId));
    }

    public CommentDto updateComment(UUID commentId, String userId, UpdateCommentRequest request) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        if (!comment.canBeEditedBy(userId)) {
            throw ApiException.forbidden(MessageKey.COMMENT_FORBIDDEN);
        }

        comment.updateContent(request.getText());
        comment = commentRepository.save(comment);

        log.info("Comment {} updated by user {}", commentId, userId);

        return convertToCommentDto(comment);
    }

    public void deleteComment(UUID commentId, String userId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        if (!comment.canBeDeletedBy(userId)) {
            throw ApiException.forbidden(MessageKey.COMMENT_FORBIDDEN);
        }

        comment.softDelete();
        commentRepository.save(comment);

        log.info("Comment {} deleted by user {}", commentId, userId);
    }

    @Transactional(readOnly = true)
    public CommentDto getComment(UUID commentId, String currentUserId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        return convertToCommentDto(comment, currentUserId);
    }

    @Transactional(readOnly = true)
    public long countCommentsByVote(String voteId) {
        if (!voteRepository.existsById(UUID.fromString(voteId))) {
            throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);
        }

        return commentRepository.countByVoteId(UUID.fromString(voteId));
    }

    public void likeComment(UUID commentId, String userId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        UUID userUuid = UUID.fromString(userId);
        if (commentLikeRepository.existsByCommentIdAndUserId(commentId, userUuid)) {
            log.warn("User {} already liked comment {}", userId, commentId);
            return;
        }

        CommentLike like = new CommentLike(comment, user);
        commentLikeRepository.save(like);

        log.info("User {} liked comment {}", userId, commentId);
    }

    public void unlikeComment(UUID commentId, String userId) {
        commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

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

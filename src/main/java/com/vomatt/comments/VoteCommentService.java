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
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        if (request.getParentId() != null) {
            comment.setParent(findReplyRoot(request.getParentId(), vote.getId()));
        }
        // flush so created_at / updated_at exist before mapping (UUIDv7 ids defer the insert)
        comment = commentRepository.saveAndFlush(comment);

        log.info("Comment created: {} on vote: {} by user: {}", comment.getId(), voteId, userId);

        return convertToCommentDto(comment);
    }

    /** A Poll's comments, newest first, cursor-paged; likes are loaded per page, not per comment. */
    @Transactional(readOnly = true)
    public CursorResponse<CommentDto> getCommentsByVote(String voteId, String cursor, Integer limit,
                                                        String currentUserId) {
        UUID voteUuid = UUID.fromString(voteId);
        if (!voteRepository.existsById(voteUuid)) {
            throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);
        }

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<VoteComment> rows = commentRepository.findPageByVoteId(voteUuid,
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, c -> Cursor.of(c.getCreatedAt(), c.getId()),
            page -> withReplyCounts(page, toDtos(page, currentUserId)));
    }

    /** Replies under one top-level Comment, oldest first, cursor-paged. */
    @Transactional(readOnly = true)
    public CursorResponse<CommentDto> getReplies(String voteId, UUID commentId, String cursor, Integer limit,
                                                 String currentUserId) {
        VoteComment root = commentRepository.findById(commentId)
            .filter(c -> c.getParent() == null && c.getVote().getId().toString().equals(voteId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<VoteComment> rows = commentRepository.findReplyPage(root.getId(),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, c -> Cursor.of(c.getCreatedAt(), c.getId()),
            page -> toDtos(page, currentUserId));
    }

    // Replies are one level deep: replying to a Reply attaches to the same top-level Comment
    private VoteComment findReplyRoot(UUID targetId, UUID voteId) {
        VoteComment target = commentRepository.findByIdAndNotDeleted(targetId)
            .filter(c -> c.getVote().getId().equals(voteId))
            .orElseThrow(() -> ApiException.badRequest(MessageKey.COMMENT_PARENT_INVALID));
        return target.getParent() != null ? target.getParent() : target;
    }

    private List<CommentDto> withReplyCounts(List<VoteComment> roots, List<CommentDto> dtos) {
        if (roots.isEmpty()) {
            return dtos;
        }
        Map<UUID, Long> replyCounts = commentRepository.countRepliesByParentIds(
                roots.stream().map(VoteComment::getId).toList()).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        dtos.forEach(dto -> dto.setReplyCount(replyCounts.getOrDefault(UUID.fromString(dto.getId()), 0L)));
        return dtos;
    }

    public CommentDto updateComment(UUID commentId, String userId, UpdateCommentRequest request) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        if (!comment.canBeEditedBy(userId)) {
            throw ApiException.forbidden(MessageKey.COMMENT_FORBIDDEN);
        }

        comment.updateContent(request.getText());
        comment = commentRepository.saveAndFlush(comment);

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

    private List<CommentDto> toDtos(List<VoteComment> comments, String currentUserId) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = comments.stream().map(VoteComment::getId).toList();
        Map<UUID, Long> likeCounts = commentLikeRepository.countByCommentIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        Set<UUID> liked = currentUserId == null ? Set.of()
            : Set.copyOf(commentLikeRepository.findLikedCommentIds(UUID.fromString(currentUserId), ids));
        return comments.stream()
            .map(c -> commentMapper.toDto(c, likeCounts.getOrDefault(c.getId(), 0L), liked.contains(c.getId())))
            .toList();
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

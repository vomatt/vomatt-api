package com.vomatt.comments;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.comments.dto.CreateCommentRequest;
import com.vomatt.comments.dto.UpdateCommentRequest;
import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.ReportCommentRequest;
import com.vomatt.entity.User;
import com.vomatt.repository.UserRepository;
import com.vomatt.entity.CommentLike;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.util.UUIDv7Generator;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.CommentReportRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.UserBlockRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.votes.VoteService;
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
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class VoteCommentService {

    private final VoteCommentRepository commentRepository;
    private final VoteService voteService;
    private final UserRepository userRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final UserBlockRepository blockRepository;
    private final CommentMapper commentMapper;
    private final CommentReportRepository commentReportRepository;

    /** Replies shown under each top-level Comment in a Poll's Comment list. */
    static final int REPLY_PREVIEW_SIZE = 2;

    public CommentDto createComment(String voteId, String userId, CreateCommentRequest request) {
        Vote vote = voteService.findAccessibleVote(UUID.fromString(voteId), userId);

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        VoteComment comment = new VoteComment(vote, user, request.getText());
        if (request.getParentId() != null) {
            comment.setParent(findReplyRoot(request.getParentId(), vote.getId()));
        }
        // flush so created_at / updated_at exist before mapping (UUIDv7 ids defer the insert)
        comment = commentRepository.saveAndFlush(comment);

        log.info("Comment created: {} on vote: {} by user: {}", comment.getId(), voteId, userId);

        return toDto(comment, null);
    }

    /** A Poll's comments, newest first, cursor-paged; likes are loaded per page, not per comment. */
    @Transactional(readOnly = true)
    public CursorResponse<CommentDto> getCommentsByVote(String voteId, String cursor, Integer limit,
                                                        String currentUserId) {
        UUID voteUuid = voteService.findAccessibleVote(UUID.fromString(voteId), currentUserId).getId();

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.COMMENTS);
        UUID viewerId = currentUserId == null ? null : UUID.fromString(currentUserId);
        List<VoteComment> rows = commentRepository.findPageByVoteId(voteUuid,
            after == null ? null : after.timeKey(), after == null ? null : after.id(), viewerId, Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.COMMENTS, c -> Cursor.of(c.getCreatedAt(), c.getId()),
            page -> toThreads(page, viewerId, currentUserId));
    }

    /** Replies under one top-level Comment, oldest first, cursor-paged. */
    @Transactional(readOnly = true)
    public CursorResponse<CommentDto> getReplies(String voteId, UUID commentId, String cursor, Integer limit,
                                                 String currentUserId) {
        voteService.findAccessibleVote(UUID.fromString(voteId), currentUserId);
        UUID viewerId = currentUserId == null ? null : UUID.fromString(currentUserId);
        VoteComment root = commentRepository.findById(commentId)
            .filter(c -> c.getParent() == null && c.getVote().getId().toString().equals(voteId))
            // A thread started by a user the viewer blocked is hidden from the list, so its Replies are too
            .filter(c -> viewerId == null || !blockRepository.existsByBlockerIdAndBlockedId(viewerId, c.getUser().getId()))
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));

        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.REPLIES);
        List<VoteComment> rows = commentRepository.findReplyPage(root.getId(),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), viewerId, Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.REPLIES, c -> Cursor.of(c.getCreatedAt(), c.getId()),
            page -> toDtos(page, currentUserId));
    }

    // A live Comment on a Poll the caller can reach; a Deleted Poll's Comments are gone with it
    private VoteComment findComment(UUID commentId, String userId) {
        VoteComment comment = commentRepository.findByIdAndNotDeleted(commentId)
            .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));
        voteService.findAccessibleVote(comment.getVote().getId(), userId);
        return comment;
    }

    // Replies are one level deep: replying to a Reply attaches to the same top-level Comment
    private VoteComment findReplyRoot(UUID targetId, UUID voteId) {
        VoteComment target = commentRepository.findByIdAndNotDeleted(targetId)
            .filter(c -> c.getVote().getId().equals(voteId))
            .orElseThrow(() -> ApiException.badRequest(MessageKey.COMMENT_PARENT_INVALID));
        return target.getParent() != null ? target.getParent() : target;
    }

    /**
     * Top-level Comments with their visible Reply counts and first Replies; likes are loaded once for both. The
     * {@code repliesNextCursor} continues {@link #getReplies} right after the last preview.
     */
    private List<CommentDto> toThreads(List<VoteComment> roots, UUID viewerId, String currentUserId) {
        if (roots.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> replyCounts = commentRepository.countRepliesByParentIds(
                roots.stream().map(VoteComment::getId).toList(), viewerId).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getCount));
        List<VoteComment> previews = replyCounts.isEmpty() ? List.of()
            : commentRepository.findReplyPreviews(replyCounts.keySet(), viewerId, REPLY_PREVIEW_SIZE);
        List<CommentDto> dtos = toDtos(Stream.concat(roots.stream(), previews.stream()).toList(), currentUserId);
        List<CommentDto> rootDtos = dtos.subList(0, roots.size());
        Map<String, List<CommentDto>> repliesByRoot = dtos.subList(roots.size(), dtos.size()).stream()
            .collect(Collectors.groupingBy(CommentDto::getParentId));
        for (CommentDto root : rootDtos) {
            long replyCount = replyCounts.getOrDefault(UUID.fromString(root.getId()), 0L);
            List<CommentDto> replies = repliesByRoot.getOrDefault(root.getId(), List.of());
            root.setReplyCount(replyCount);
            root.setReplies(replies);
            // Empty only if every Reply went between the two reads; then there is nothing to continue from
            if (!replies.isEmpty() && replyCount > replies.size()) {
                CommentDto last = replies.getLast();
                root.setRepliesNextCursor(Cursor.of(last.getCreatedAt(), UUID.fromString(last.getId()))
                    .encode(Cursor.Scope.REPLIES));
            }
        }
        return rootDtos;
    }

    public CommentDto updateComment(UUID commentId, String userId, UpdateCommentRequest request) {
        VoteComment comment = findComment(commentId, userId);

        if (!comment.canBeEditedBy(userId)) {
            throw ApiException.forbidden(MessageKey.COMMENT_FORBIDDEN);
        }

        comment.updateContent(request.getText());
        comment = commentRepository.saveAndFlush(comment);

        log.info("Comment {} updated by user {}", commentId, userId);

        return toDto(comment, null);
    }

    public void deleteComment(UUID commentId, String userId) {
        VoteComment comment = findComment(commentId, userId);

        if (!comment.canBeDeletedBy(userId)) {
            throw ApiException.forbidden(MessageKey.COMMENT_FORBIDDEN);
        }

        comment.softDelete();
        commentRepository.save(comment);

        log.info("Comment {} deleted by user {}", commentId, userId);
    }

    @Transactional(readOnly = true)
    public CommentDto getComment(UUID commentId, String currentUserId) {
        VoteComment comment = findComment(commentId, currentUserId);

        return toDto(comment, currentUserId);
    }

    public void likeComment(UUID commentId, String userId) {
        VoteComment comment = findComment(commentId, userId);

        UUID userUuid = UUID.fromString(userId);
        User user = userRepository.findById(userUuid)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        if (commentLikeRepository.existsByCommentIdAndUserId(commentId, userUuid)) {
            log.warn("User {} already liked comment {}", userId, commentId);
            return;
        }

        CommentLike like = new CommentLike(comment, user);
        commentLikeRepository.save(like);

        log.info("User {} liked comment {}", userId, commentId);
    }

    /** Reports a live Comment or Reply of someone else; idempotent, a repeat report keeps the first one. */
    public void reportComment(UUID commentId, String userId, ReportCommentRequest request) {
        VoteComment comment = findComment(commentId, userId);
        UUID reporterId = UUID.fromString(userId);
        if (comment.getUser().getId().equals(reporterId)) {
            throw ApiException.badRequest(MessageKey.COMMENT_REPORT_SELF);
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (commentReportRepository.insertIfAbsent(UUIDv7Generator.generateId(), commentId, reporterId,
                request.reason().name(), note) > 0) {
            log.info("User {} reported comment {} as {}", userId, commentId, request.reason());
        }
    }

    public void unlikeComment(UUID commentId, String userId) {
        findComment(commentId, userId);

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

    private CommentDto toDto(VoteComment comment, String currentUserId) {
        return toDtos(List.of(comment), currentUserId).getFirst();
    }
}

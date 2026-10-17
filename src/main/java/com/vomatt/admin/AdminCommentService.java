package com.vomatt.admin;

import static com.vomatt.admin.AdminService.blankToNull;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminCommentResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.PageResponse;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.VoteCommentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Comment moderation: any Comment on any Poll, deleted and on Deleted Polls included. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminCommentService {

    private final AdminQueryRepository adminQueryRepository;
    private final VoteCommentRepository commentRepository;
    private final AdminMapper adminMapper;

    @Transactional(readOnly = true)
    public PageResponse<AdminCommentResponse> listComments(UUID voteId, UUID userId, String q, Boolean deleted,
                                                           Integer page, Integer limit) {
        return PageResponse.from(adminQueryRepository.findComments(voteId, userId, blankToNull(q), deleted,
                PageResponse.pageable(page, limit)), adminMapper::toCommentResponse);
    }

    /** Soft delete, as the author's own delete; idempotent. Replies stay, the Comment becomes their placeholder. */
    public void deleteComment(UUID commentId, String actorId) {
        VoteComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMENT_NOT_FOUND));
        comment.softDelete();
        log.info("Comment {} deleted by admin {}", commentId, actorId);
    }
}

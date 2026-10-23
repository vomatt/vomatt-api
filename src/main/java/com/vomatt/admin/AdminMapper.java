package com.vomatt.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.vomatt.admin.dto.AdminCommentResponse;
import com.vomatt.admin.dto.AdminFeedbackResponse;
import com.vomatt.admin.dto.AdminUserBlockResponse;
import com.vomatt.admin.dto.AdminUserDetail;
import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.admin.dto.AuditLogResponse;
import com.vomatt.common.audit.AuditLog;
import com.vomatt.common.util.TextUtil;
import com.vomatt.entity.Feedback;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.VoteComment;

/**
 * Back-office views; unlike the public mappers nothing is masked (deleted Comments keep author and text), except the
 * user list: an admin session that leaks must not hand over every email and phone number in one request, so the list
 * masks contact details and the detail view (audited per lookup) holds the full record.
 */
@Component
public class AdminMapper {

    public AdminUserResponse toUserResponse(User user) {
        return new AdminUserResponse(user.getId().toString(), user.getUsername(), user.getDisplayName(),
                user.getEmail() == null ? null : TextUtil.maskEmail(user.getEmail()),
                user.getPhoneNumber() == null ? null : TextUtil.maskPhone(user.getPhoneNumber()),
                user.getAuthMethod(), List.of(user.getRoles()), user.isActive(),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    public AdminUserDetail toUserDetail(User user, long pollCount, long ballotCount, long commentCount,
                                        long blockingCount, long blockedByCount) {
        return new AdminUserDetail(user.getId().toString(), user.getUsername(), user.getDisplayName(),
                user.getFirstName(), user.getLastName(), user.getBirthday(), user.getEmail(),
                user.getPhoneNumber(), user.getAuthMethod(), List.of(user.getRoles()), user.isActive(),
                user.getLastLoginAt(), user.getPoints(), user.getMembershipLevel(), user.getCreatedAt(),
                pollCount, ballotCount, commentCount, blockingCount, blockedByCount);
    }

    /** {@code other} is the side of the block that is not the user being looked at. */
    public AdminUserBlockResponse toBlockResponse(UserBlock block, User other) {
        return new AdminUserBlockResponse(other.getId().toString(), other.getUsername(), other.getDisplayName(),
                other.isActive(), block.getCreatedAt());
    }

    public AdminCommentResponse toCommentResponse(VoteComment comment) {
        return new AdminCommentResponse(comment.getId().toString(), comment.getVote().getId().toString(),
                comment.getVote().getTitle(), comment.getParent() == null ? null : comment.getParent().getId().toString(),
                comment.getUser().getId().toString(), comment.getUser().getUsername(), comment.getContent(),
                comment.isDeleted(), comment.getCreatedAt(), comment.getUpdatedAt());
    }

    public AdminFeedbackResponse toFeedbackResponse(Feedback feedback) {
        return new AdminFeedbackResponse(feedback.getId().toString(), feedback.getUser().getId().toString(),
                feedback.getUser().getUsername(), feedback.getContent(), feedback.getCreatedAt());
    }

    public AuditLogResponse toAuditLogResponse(AuditLog log) {
        return new AuditLogResponse(log.getId().toString(), log.getUserId(), log.getUsername(), log.getAction(),
                log.getResourceType(), log.getResourceId(), log.getIpAddress(), log.getUserAgent(), log.getRequestUri(),
                log.getHttpMethod(), log.isSuccess(), log.getErrorMessage(), log.getDetails(), log.getCreatedAt());
    }
}

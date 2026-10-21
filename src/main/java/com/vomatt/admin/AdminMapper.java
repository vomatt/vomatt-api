package com.vomatt.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.vomatt.admin.dto.AdminCommentResponse;
import com.vomatt.admin.dto.AdminUserDetail;
import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.admin.dto.AuditLogResponse;
import com.vomatt.common.audit.AuditLog;
import com.vomatt.entity.User;
import com.vomatt.entity.VoteComment;

/** Back-office views; unlike the public mappers nothing is masked (deleted Comments keep author and text). */
@Component
public class AdminMapper {

    public AdminUserResponse toUserResponse(User user) {
        return new AdminUserResponse(user.getId().toString(), user.getUsername(), user.getDisplayName(),
                user.getFirstName(), user.getLastName(), user.getBirthday(), user.getEmail(),
                user.getPhoneNumber(), user.getAuthMethod(), List.of(user.getRoles()), user.isActive(),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    public AdminUserDetail toUserDetail(User user, long pollCount, long ballotCount, long commentCount) {
        return new AdminUserDetail(user.getId().toString(), user.getUsername(), user.getDisplayName(),
                user.getFirstName(), user.getLastName(), user.getBirthday(), user.getEmail(),
                user.getPhoneNumber(), user.getAuthMethod(), List.of(user.getRoles()), user.isActive(),
                user.getLastLoginAt(), user.getPoints(), user.getMembershipLevel(), user.getCreatedAt(),
                pollCount, ballotCount, commentCount);
    }

    public AdminCommentResponse toCommentResponse(VoteComment comment) {
        return new AdminCommentResponse(comment.getId().toString(), comment.getVote().getId().toString(),
                comment.getVote().getTitle(), comment.getParent() == null ? null : comment.getParent().getId().toString(),
                comment.getUser().getId().toString(), comment.getUser().getUsername(), comment.getContent(),
                comment.isDeleted(), comment.getCreatedAt(), comment.getUpdatedAt());
    }

    public AuditLogResponse toAuditLogResponse(AuditLog log) {
        return new AuditLogResponse(log.getId().toString(), log.getUserId(), log.getUsername(), log.getAction(),
                log.getResourceType(), log.getResourceId(), log.getIpAddress(), log.getUserAgent(), log.getRequestUri(),
                log.getHttpMethod(), log.isSuccess(), log.getErrorMessage(), log.getDetails(), log.getCreatedAt());
    }
}

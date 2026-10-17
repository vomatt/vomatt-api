package com.vomatt.admin;

import static com.vomatt.admin.AdminService.blankToNull;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminUserDetail;
import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.common.constant.UserRole;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.PageResponse;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.util.AfterCommit;
import com.vomatt.entity.User;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.users.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Account moderation. Guard rails: an admin cannot suspend themself or drop their own admin role, and the last active
 * admin can be neither suspended nor demoted. Suspension and role changes revoke every refresh token, so the user signs
 * in again (role changes then take effect; access tokens already issued keep their roles until they expire).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserService {

    private final AdminQueryRepository adminQueryRepository;
    private final UserRepository userRepository;
    private final VoteRepository voteRepository;
    private final UserVoteRepository userVoteRepository;
    private final VoteCommentRepository voteCommentRepository;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;
    private final AdminMapper adminMapper;

    /** @param status active, suspended, or null for both */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(String q, String status, String role, Integer page, Integer limit) {
        String statusFilter = blankToNull(status);
        Boolean active = switch (statusFilter == null ? "" : statusFilter) {
            case "" -> null;
            case "active" -> true;
            case "suspended" -> false;
            default -> throw ApiException.badRequest(MessageKey.COMMON_INVALID_STATUS, status);
        };
        String roleFilter = blankToNull(role);
        if (roleFilter != null && !roleFilter.matches(UserRole.PATTERN)) {
            throw ApiException.badRequest(MessageKey.COMMON_VALIDATION_FAILED);
        }
        return PageResponse.from(adminQueryRepository.findUsers(blankToNull(q), active, roleFilter,
                PageResponse.pageable(page, limit)), adminMapper::toUserResponse);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail getUser(UUID userId) {
        return detail(findUser(userId));
    }

    public AdminUserDetail suspend(UUID userId, String actorId) {
        User user = findUser(userId);
        if (user.getId().toString().equals(actorId)) {
            throw ApiException.forbidden(MessageKey.ADMIN_SELF_ACTION_FORBIDDEN);
        }
        userService.requireAnotherActiveAdmin(user);
        user.setActive(false);
        // After commit: a sign-in or refresh in between would otherwise still see the account active
        AfterCommit.run(() -> refreshTokenService.revokeAllForUser(userId.toString()));
        log.info("User {} suspended by admin {}", userId, actorId);
        return detail(user);
    }

    public AdminUserDetail unsuspend(UUID userId, String actorId) {
        User user = findUser(userId);
        user.setActive(true);
        log.info("User {} unsuspended by admin {}", userId, actorId);
        return detail(user);
    }

    public AdminUserDetail updateRoles(UUID userId, List<String> roles, String actorId) {
        User user = findUser(userId);
        // Unchanged role set: nothing to write and no reason to sign the user out
        if (Set.copyOf(roles).equals(Set.copyOf(Arrays.asList(user.getRoles())))) {
            return detail(user);
        }
        if (user.isAdmin() && !roles.contains(UserRole.ADMIN)) {
            if (user.getId().toString().equals(actorId)) {
                throw ApiException.forbidden(MessageKey.ADMIN_SELF_ACTION_FORBIDDEN);
            }
            userService.requireAnotherActiveAdmin(user);
        }
        user.setRoles(roles.stream().distinct().toArray(String[]::new));
        AfterCommit.run(() -> refreshTokenService.revokeAllForUser(userId.toString()));
        log.info("User {} roles set to {} by admin {}", userId, roles, actorId);
        return detail(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
    }

    private AdminUserDetail detail(User user) {
        UUID id = user.getId();
        return adminMapper.toUserDetail(user, voteRepository.countByCreatorId(id), userVoteRepository.countByUserId(id),
                voteCommentRepository.countByUserId(id));
    }
}

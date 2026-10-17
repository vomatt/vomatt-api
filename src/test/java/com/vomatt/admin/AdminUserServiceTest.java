package com.vomatt.admin;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.User;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.users.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserService")
class AdminUserServiceTest {

    @Mock AdminQueryRepository adminQueryRepository;
    @Mock UserRepository userRepository;
    @Mock VoteRepository voteRepository;
    @Mock UserVoteRepository userVoteRepository;
    @Mock VoteCommentRepository voteCommentRepository;
    @Mock RefreshTokenService refreshTokenService;
    @Mock LockTimeout lockTimeout;

    AdminUserService service;

    final UUID actorId = UUID.randomUUID();
    final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The real guard, so these tests cover the shared last-admin rule as admins reach it
        UserService userService = new UserService(userRepository, null, null, refreshTokenService, null, voteRepository, lockTimeout);
        service = new AdminUserService(adminQueryRepository, userRepository, voteRepository, userVoteRepository,
                voteCommentRepository, refreshTokenService, userService, new AdminMapper());
    }

    private User user(UUID id, String... roles) {
        User user = User.builder().username("u-" + id).roles(roles).build();
        ReflectionTestUtils.setField(user, "id", id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private static void assertApiError(Runnable call, HttpStatus status, MessageKey key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }

    @Test
    void shouldSuspendAndRevokeTokensWhenTargetIsRegularUser() {
        User target = user(targetId, "user");

        service.suspend(targetId, actorId.toString());

        assertThat(target.isActive()).isFalse();
        verify(refreshTokenService).revokeAllForUser(targetId.toString());
    }

    @Test
    void shouldRefuseWhenAdminSuspendsThemself() {
        User self = user(actorId, "user", "admin");

        assertApiError(() -> service.suspend(actorId, actorId.toString()), HttpStatus.FORBIDDEN, MessageKey.ADMIN_SELF_ACTION_FORBIDDEN);
        assertThat(self.isActive()).isTrue();
        verify(refreshTokenService, never()).revokeAllForUser(anyString());
    }

    @Test
    void shouldRefuseWhenSuspendingLastActiveAdmin() {
        User target = user(targetId, "admin");
        // the target is the only active admin
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(targetId));

        assertApiError(() -> service.suspend(targetId, actorId.toString()), HttpStatus.CONFLICT, MessageKey.ADMIN_LAST_ADMIN);
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void shouldSuspendAdminWhenAnotherActiveAdminRemains() {
        User target = user(targetId, "admin");
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(targetId, actorId));

        service.suspend(targetId, actorId.toString());

        assertThat(target.isActive()).isFalse();
    }

    @Test
    void shouldReactivateWhenUnsuspending() {
        User target = user(targetId, "user");
        target.setActive(false);

        assertThat(service.unsuspend(targetId, actorId.toString()).active()).isTrue();
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void shouldReturn404WhenUserMissing() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertApiError(() -> service.unsuspend(targetId, actorId.toString()), HttpStatus.NOT_FOUND, MessageKey.USER_NOT_FOUND);
    }

    @Test
    void shouldReplaceRolesAndRevokeTokensWhenValid() {
        User target = user(targetId, "user");

        var detail = service.updateRoles(targetId, List.of("user", "moderator", "user"), actorId.toString());

        assertThat(target.getRoles()).containsExactly("user", "moderator");
        assertThat(detail.roles()).containsExactly("user", "moderator");
        verify(refreshTokenService).revokeAllForUser(targetId.toString());
    }

    @Test
    void shouldKeepTokensWhenRoleSetUnchanged() {
        User target = user(targetId, "user", "moderator");

        service.updateRoles(targetId, List.of("moderator", "user", "user"), actorId.toString());

        assertThat(target.getRoles()).containsExactly("user", "moderator");
        verify(refreshTokenService, never()).revokeAllForUser(anyString());
    }

    @Test
    void shouldRevokeTokensOnlyAfterCommitWhenSuspending() {
        user(targetId, "user");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.suspend(targetId, actorId.toString());

            verify(refreshTokenService, never()).revokeAllForUser(anyString());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(refreshTokenService).revokeAllForUser(targetId.toString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldTreatBlankStatusAsUnsetWhenListing() {
        when(adminQueryRepository.findUsers(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listUsers(null, "  ", null, null, null);

        verify(adminQueryRepository).findUsers(isNull(), isNull(), isNull(), any());
    }

    @Test
    void shouldRefuseWhenAdminDropsOwnAdminRole() {
        User self = user(actorId, "user", "admin");

        assertApiError(() -> service.updateRoles(actorId, List.of("user"), actorId.toString()),
                HttpStatus.FORBIDDEN, MessageKey.ADMIN_SELF_ACTION_FORBIDDEN);
        assertThat(self.getRoles()).containsExactly("user", "admin");
    }

    @Test
    void shouldAllowWhenAdminChangesOwnRolesKeepingAdmin() {
        User self = user(actorId, "user", "admin");

        service.updateRoles(actorId, List.of("user", "moderator", "admin"), actorId.toString());

        assertThat(self.getRoles()).containsExactly("user", "moderator", "admin");
        verify(userRepository, never()).lockActiveAdminIds();
    }

    @Test
    void shouldRefuseWhenDemotingLastActiveAdmin() {
        user(targetId, "admin");
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(targetId));

        assertApiError(() -> service.updateRoles(targetId, List.of("user"), actorId.toString()),
                HttpStatus.CONFLICT, MessageKey.ADMIN_LAST_ADMIN);
    }

    @Test
    void shouldDemoteSuspendedAdminWithoutLastAdminCheckWhenTargetInactive() {
        User target = user(targetId, "admin");
        target.setActive(false);

        service.updateRoles(targetId, List.of("user"), actorId.toString());

        assertThat(target.getRoles()).containsExactly("user");
        verify(userRepository, never()).lockActiveAdminIds();
    }

    @Test
    void shouldRejectRoleFilterWhenUnknown() {
        assertApiError(() -> service.listUsers(null, null, "superuser", null, null), HttpStatus.BAD_REQUEST, MessageKey.COMMON_VALIDATION_FAILED);
    }

    @Test
    void shouldRejectStatusWhenNotActiveOrSuspended() {
        assertApiError(() -> service.listUsers(null, "banned", null, null, null), HttpStatus.BAD_REQUEST, MessageKey.COMMON_INVALID_STATUS);
    }
}

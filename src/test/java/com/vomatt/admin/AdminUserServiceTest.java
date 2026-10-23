package com.vomatt.admin;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.entity.User;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.UserBlockRepository;
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
    @Mock UserBlockRepository userBlockRepository;
    @Mock RefreshTokenService refreshTokenService;
    @Mock com.vomatt.auth.AuthService authService;
    LockTimeout lockTimeout = new LockTimeout(org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

    AdminUserService service;

    final UUID actorId = UUID.randomUUID();
    final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The real guard, so these tests cover the shared last-admin rule as admins reach it
        UserService userService = new UserService(userRepository, null, null, null, refreshTokenService, null, voteRepository, lockTimeout, null);
        service = new AdminUserService(adminQueryRepository, userRepository, voteRepository, userVoteRepository,
                voteCommentRepository, userBlockRepository, userService, new AdminMapper(), authService);
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
    void shouldRejectWhenBlockDirectionIsUnknown() {
        user(targetId, "user");
        assertThatThrownBy(() -> service.listBlocks(targetId, "sideways", null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_INVALID_DIRECTION));
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

    @Test
    void shouldUnlockEveryChannelAndTargetWhenRequestIsEmpty() {
        UUID id = UUID.randomUUID();
        User target = user(id, "user");
        target.setEmail("a@example.com");
        target.setPhoneNumber("+886912345678");
        when(userRepository.findById(id)).thenReturn(Optional.of(target));
        java.util.Set<String> all = java.util.Set.of("lock", "attempts", "cooldown");
        when(authService.unlockLogin("email", "a@example.com", null, all)).thenReturn(2);
        when(authService.unlockLogin("admin", "a@example.com", null, all)).thenReturn(1);
        when(authService.unlockLogin("phone", "+886912345678", null, all)).thenReturn(0);

        var result = service.unlockLogin(id, new com.vomatt.admin.dto.UnlockLoginRequest(null, null, null), "actor");

        assertThat(result.clearedEntries()).isEqualTo(3);
    }

    @Test
    void shouldSkipChannelWhenUserHasNoSuchIdentifier() {
        UUID id = UUID.randomUUID();
        User target = user(id, "user");
        target.setEmail("a@example.com");
        when(userRepository.findById(id)).thenReturn(Optional.of(target));
        when(authService.unlockLogin("email", "a@example.com", "203.0.113.5", java.util.Set.of("lock"))).thenReturn(1);

        var result = service.unlockLogin(id, new com.vomatt.admin.dto.UnlockLoginRequest(
                java.util.List.of("email", "phone"), "203.0.113.5", java.util.List.of("lock")), "actor");

        assertThat(result.clearedEntries()).isEqualTo(1);
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                .unlockLogin(org.mockito.ArgumentMatchers.eq("phone"), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}

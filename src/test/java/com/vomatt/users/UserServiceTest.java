package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.UserPreference;
import com.vomatt.users.dto.UserDto;
import com.vomatt.repository.UserBlockRepository;
import com.vomatt.repository.UserFollowRepository;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.repository.UserProfileProjection;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserPreferenceRepository preferenceRepository;
    @Mock UserBlockRepository blockRepository;
    @Mock UserFollowRepository followRepository;
    @Spy UserMapper userMapper = new UserMapper();
    @Mock RefreshTokenService refreshTokenService;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock VoteRepository voteRepository;
    @Mock com.vomatt.common.audit.AuditLogRepository auditLogRepository;
    @Mock com.vomatt.repository.UserVoteRepository userVoteRepository;
    @Mock com.vomatt.repository.VoteCommentRepository voteCommentRepository;
    @Mock com.vomatt.repository.FeedbackRepository feedbackRepository;
    @Mock com.vomatt.common.redis.RedisService redisService;
    @Spy com.vomatt.repository.LockTimeout lockTimeout = new com.vomatt.repository.LockTimeout(org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
    @InjectMocks UserService userService;

    @Test
    void shouldRejectWhenBlockingSelf() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(User.builder().id(id).username("u").build()));

        assertThatThrownBy(() -> userService.blockUser(id.toString(), id.toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_BLOCK_SELF));
        verify(blockRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldRejectWhenFollowingSelf() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(User.builder().id(id).username("u").build()));

        assertThatThrownBy(() -> userService.followUser(id.toString(), id.toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_FOLLOW_SELF));
        verify(followRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void shouldRejectAsNotFoundWhenFollowingSuspendedOrUnknownUser() {
        UUID suspended = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(suspended))
                .thenReturn(Optional.of(User.builder().id(suspended).username("s").active(false).build()));
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());
        String caller = UUID.randomUUID().toString();

        for (UUID target : List.of(suspended, unknown)) {
            assertThatThrownBy(() -> userService.followUser(caller, target.toString()))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
            assertThatThrownBy(() -> userService.blockUser(caller, target.toString()))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        }
        verify(followRepository, never()).insertIfAbsent(any(), any(), any());
        verify(blockRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("should export the caller's own data once per day")
    void shouldExportOwnDataOncePerDay() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").email("alice@example.com").roles(new String[] { "user" }).build();
        com.vomatt.entity.Vote poll = new com.vomatt.entity.Vote("Lunch?", null, user, java.time.OffsetDateTime.now().plusDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(poll, "id", UUID.randomUUID());
        com.vomatt.entity.VoteOption option = new com.vomatt.entity.VoteOption("Ramen", null, poll);
        org.springframework.test.util.ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
        poll.addOption(option);
        com.vomatt.entity.UserVote sealed = new com.vomatt.entity.UserVote(user, poll, null);
        sealed.setAnonymous(true);
        when(redisService.get(eq("export:daily"), org.mockito.ArgumentMatchers.startsWith(id + ":"), eq(Long.class))).thenReturn(null, 1L);
        when(redisService.incrementAndExpire(eq("export:daily"), org.mockito.ArgumentMatchers.startsWith(id + ":"), eq(java.time.Duration.ofDays(1))))
                .thenReturn(1L);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        com.vomatt.repository.UserProfileProjection projection = org.mockito.Mockito.mock(com.vomatt.repository.UserProfileProjection.class);
        when(projection.getTotalPolls()).thenReturn(1L);
        when(projection.getTotalVotes()).thenReturn(1L);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(projection));
        when(preferenceRepository.findByUserId(id)).thenReturn(List.of());
        when(voteRepository.findByCreatorIdOrderByCreatedAtDesc(id)).thenReturn(List.of(poll));
        when(voteCommentRepository.findByUserId(id)).thenReturn(List.of());
        when(userVoteRepository.findByUserIdWithVoteAndOption(id)).thenReturn(List.of(sealed));
        com.vomatt.entity.Feedback feedback = org.mockito.Mockito.mock(com.vomatt.entity.Feedback.class);
        when(feedback.getId()).thenReturn(UUID.randomUUID());
        when(feedback.getContent()).thenReturn("Please add dark mode.");
        when(feedbackRepository.findByUserIdOrderByCreatedAt(id)).thenReturn(List.of(feedback));

        com.vomatt.users.dto.UserDataExportResponse export = userService.exportMyData(id.toString());

        assertThat(export.polls()).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Lunch?");
            assertThat(p.options()).containsExactly("Ramen");
        });
        assertThat(export.ballots()).singleElement().satisfies(b -> {
            assertThat(b.optionId()).isNull();
            assertThat(b.anonymous()).isTrue();
        });
        assertThat(export.feedback()).extracting(f -> f.content()).containsExactly("Please add dark mode.");
        assertThatThrownBy(() -> userService.exportMyData(id.toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("should release user's Ballot from option counts and Turnout before deleting account")
    void shouldReleaseBallotCountsBeforeDeletingUser() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("u").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("user")));

        InOrder order = inOrder(voteOptionRepository, voteRepository, auditLogRepository, userRepository);
        order.verify(voteRepository).decrementTurnoutForUserBallots(id);
        order.verify(voteOptionRepository).decrementForUserBallots(id);
        order.verify(auditLogRepository).anonymizeByUserId(id.toString());
        order.verify(userRepository).delete(user);
    }

    @Test
    void shouldRefuseWhenDeletingLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        User admin = User.builder().id(id).username("root").roles(new String[] { "user", "admin" }).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(admin));
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(id));

        assertThatThrownBy(() -> userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("admin"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.ADMIN_LAST_ADMIN));
        verify(userRepository, never()).delete(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void shouldDeleteAdminWhenAnotherActiveAdminRemains() {
        UUID id = UUID.randomUUID();
        User admin = User.builder().id(id).username("root").roles(new String[] { "admin" }).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(admin));
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(id, UUID.randomUUID()));

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("admin")));

        verify(userRepository).delete(admin);
    }

    @Test
    void shouldRefuseWhenDemotedAdminDeletesOtherUserWithStaleToken() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.isActiveAdmin(callerId)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(targetId.toString(),
                new UserPrincipal(callerId.toString(), List.of("user", "admin"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_DELETE_FORBIDDEN));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void shouldRefuseWhenAdminDeletesOtherUserWithConsumerToken() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        // A consumer-app token of an admin carries no admin role (JwtUtil drops it), so the DB is never consulted
        assertThatThrownBy(() -> userService.deleteUser(targetId.toString(),
                new UserPrincipal(callerId.toString(), List.of("user"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_DELETE_FORBIDDEN));
        verify(userRepository, never()).isActiveAdmin(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    void shouldDeleteOtherUserWhenCallerIsActiveAdminInDatabase() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).username("t").build();
        when(userRepository.isActiveAdmin(callerId)).thenReturn(true);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        userService.deleteUser(targetId.toString(), new UserPrincipal(callerId.toString(), List.of("admin")));

        verify(userRepository).delete(target);
    }

    @Test
    void shouldLetLockTimeoutThroughWhenAdminRowsStayLocked() {
        User admin = User.builder().id(UUID.randomUUID()).username("a").roles(new String[] { "admin" }).build();
        when(userRepository.lockActiveAdminIds())
                .thenThrow(new org.springframework.dao.PessimisticLockingFailureException("busy"));

        // GlobalExceptionHandler maps it to 409 common.conflict
        assertThatThrownBy(() -> userService.requireAnotherActiveAdmin(admin))
                .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
    }

    @Test
    void shouldLockAdminRowsUnderScopedTimeoutWhenGuarding() {
        UUID id = UUID.randomUUID();
        User admin = User.builder().id(id).username("a").roles(new String[] { "admin" }).build();
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(id, UUID.randomUUID()));

        userService.requireAnotherActiveAdmin(admin);

        verify(lockTimeout).within(any());
        verify(userRepository).lockActiveAdminIds();
    }

    @Test
    void shouldRevokeTokensOnlyAfterCommitWhenDeletingUser() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("u").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("user")));

            verify(refreshTokenService, never()).revokeAllForUser(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(refreshTokenService).revokeAllForUser(id.toString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldNotThrowAfterCommitWhenTokenRevocationFails() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(refreshTokenService).revokeAllForUser(id.toString());
        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.revokeTokensAfterCommit(id.toString());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(refreshTokenService).revokeAllForUser(id.toString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldSkipAdminLockWhenAccountIsNotActiveAdmin() {
        User suspendedAdmin = User.builder().id(UUID.randomUUID()).username("x").roles(new String[] { "admin" }).active(false).build();

        userService.requireAnotherActiveAdmin(suspendedAdmin);
        userService.requireAnotherActiveAdmin(User.builder().id(UUID.randomUUID()).username("y").build());

        verify(userRepository, never()).lockActiveAdminIds();
    }

    @Test
    @DisplayName("should set avatarUrl when given and keep it when null")
    void shouldUpdateAvatarUrlWhenGiven() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").avatarUrl("bot:boba").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserProfileProjection profile = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(profile.getActive()).thenReturn(true);
        when(profile.getId()).thenReturn(id);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(profile));
        UpdateProfileRequest request = new UpdateProfileRequest();

        userService.updateMyProfile(id.toString(), request);
        assertThat(user.getAvatarUrl()).isEqualTo("bot:boba");

        request.setAvatarUrl("bot:dog");
        userService.updateMyProfile(id.toString(), request);
        assertThat(user.getAvatarUrl()).isEqualTo("bot:dog");
    }

    @Test
    @DisplayName("should update name and birthday when given and leave them alone when null")
    void shouldUpdateNameAndBirthdayWhenGiven() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").firstName("Old").lastName("Name")
                .birthday(java.time.LocalDate.of(1990, 1, 1)).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserProfileProjection profile = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(profile.getActive()).thenReturn(true);
        when(profile.getId()).thenReturn(id);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(profile));
        UpdateProfileRequest request = new UpdateProfileRequest();

        userService.updateMyProfile(id.toString(), request);
        assertThat(user.getFirstName()).isEqualTo("Old");
        assertThat(user.getLastName()).isEqualTo("Name");
        assertThat(user.getBirthday()).isEqualTo(java.time.LocalDate.of(1990, 1, 1));

        request.setFirstName("Alice");
        request.setLastName("Chen");
        request.setBirthday(java.time.LocalDate.of(1995, 4, 12));
        userService.updateMyProfile(id.toString(), request);
        assertThat(user.getFirstName()).isEqualTo("Alice");
        assertThat(user.getLastName()).isEqualTo("Chen");
        assertThat(user.getBirthday()).isEqualTo(java.time.LocalDate.of(1995, 4, 12));

        request.setFirstName("  Al ");
        request.setLastName("   ");
        userService.updateMyProfile(id.toString(), request);
        assertThat(user.getFirstName()).isEqualTo("Al");
        assertThat(user.getLastName()).isNull();
    }

    @Test
    @DisplayName("should store anonymousBallotByDefault when given and leave it alone when null")
    void shouldSaveAnonymousBallotDefaultWhenGiven() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserProfileProjection profile = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(profile.getActive()).thenReturn(true);
        when(profile.getId()).thenReturn(id);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(profile));
        UpdateProfileRequest request = new UpdateProfileRequest();

        userService.updateMyProfile(id.toString(), request);
        verify(preferenceRepository, never()).save(any());

        request.setAnonymousBallotByDefault(true);
        userService.updateMyProfile(id.toString(), request);
        verify(preferenceRepository).save(org.mockito.ArgumentMatchers.argThat(pref ->
                UserPreference.ANONYMOUS_BALLOT_KEY.equals(pref.getKey()) && "true".equals(pref.getValue()) && pref.getUser() == user));
    }

    @Test
    @DisplayName("should reject username already used by another account")
    void shouldThrowConflictWhenUsernameTaken() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("old_name").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("taken")).thenReturn(true);
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("taken");

        assertThatThrownBy(() -> userService.updateMyProfile(id.toString(), request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(user.getUsername()).isEqualTo("old_name");
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("should return public fields and mask hidden display name when searching users")
    void shouldMaskHiddenDisplayNameWhenSearchingUsers() {
        User shown = User.builder().id(UUID.randomUUID()).username("alice").displayName("Alice")
                .avatarUrl("https://cdn.example.com/a.jpg").email("alice@example.com").build();
        User hidden = User.builder().id(UUID.randomUUID()).username("alicia").displayName("Alicia")
                .email("alicia@example.com").build();
        UserPreference pref = new UserPreference();
        pref.setUser(hidden);
        pref.setKey("visibility.displayName");
        pref.setValue("false");
        when(userRepository.searchByUsernameAfter(eq("ali"), any(), any(Limit.class))).thenReturn(List.of(shown, hidden));
        when(preferenceRepository.findByUserIdInAndKey(List.of(shown.getId(), hidden.getId()), "visibility.displayName"))
                .thenReturn(List.of(pref));

        CursorResponse<UserDto> page = userService.searchUsersByUsername("ali", null, null);

        assertThat(page.items()).containsExactly(
                new UserDto(shown.getId().toString(), "alice", "Alice", "https://cdn.example.com/a.jpg"),
                new UserDto(hidden.getId().toString(), "alicia", null, null));
        assertThat(page.nextCursor()).isNull();
    }

    private UserProfileProjection activeProfile(UUID id) {
        UserProfileProjection projection = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getActive()).thenReturn(true);
        when(projection.getTotalPolls()).thenReturn(0L);
        when(projection.getTotalVotes()).thenReturn(0L);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(projection));
        return projection;
    }

    @Test
    @DisplayName("should report whether the viewer follows and blocked the user when signed in")
    void shouldReturnRelationFlagsWhenViewerSignedIn() {
        UUID target = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        activeProfile(target);
        when(followRepository.existsByFollowerIdAndFollowedId(viewer, target)).thenReturn(true);
        when(blockRepository.existsByBlockerIdAndBlockedId(viewer, target)).thenReturn(false);

        UserProfileResponse profile = userService.getUserProfile("alice", false, viewer.toString());

        assertThat(profile.id()).isEqualTo(target.toString());
        assertThat(profile.isFollowing()).isTrue();
        assertThat(profile.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("should return false for both relation flags on the viewer's own profile")
    void shouldReturnFalseFlagsWhenViewingOwnProfile() {
        UUID self = UUID.randomUUID();
        activeProfile(self);

        UserProfileResponse profile = userService.getUserProfile("alice", false, self.toString());

        assertThat(profile.isFollowing()).isFalse();
        assertThat(profile.isBlocked()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(followRepository, blockRepository);
    }

    @Test
    @DisplayName("should return null relation flags when signed out")
    void shouldReturnNullFlagsWhenSignedOut() {
        activeProfile(UUID.randomUUID());

        UserProfileResponse profile = userService.getUserProfile("alice", false, null);

        assertThat(profile.isFollowing()).isNull();
        assertThat(profile.isBlocked()).isNull();
        org.mockito.Mockito.verifyNoInteractions(followRepository, blockRepository);
    }

    @Test
    @DisplayName("should list a user's followers with display names masked by each follower's setting")
    void shouldMaskHiddenDisplayNameWhenListingFollowers() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        User hidden = User.builder().id(UUID.randomUUID()).username("bob").displayName("Bob").build();
        UserPreference pref = new UserPreference();
        pref.setUser(hidden);
        pref.setKey("visibility.displayName");
        pref.setValue("false");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findFollowersPage(eq(alice.getId()), eq(null), eq(null), any(Limit.class)))
                .thenReturn(List.of(new UserFollow(hidden, alice)));
        when(preferenceRepository.findByUserIdInAndKey(List.of(hidden.getId()), "visibility.displayName"))
                .thenReturn(List.of(pref));

        CursorResponse<UserDto> page = userService.listFollowers("alice", null, null);

        assertThat(page.items()).containsExactly(new UserDto(hidden.getId().toString(), "bob", null, null));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should list the users a user follows")
    void shouldListFollowedUsersWhenReadingFollowing() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        User bob = User.builder().id(UUID.randomUUID()).username("bob").displayName("Bob").build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findPage(eq(alice.getId()), eq(null), eq(null), any(Limit.class)))
                .thenReturn(List.of(new UserFollow(alice, bob)));

        CursorResponse<UserDto> page = userService.listFollowing("alice", null, null);

        assertThat(page.items()).containsExactly(new UserDto(bob.getId().toString(), "bob", "Bob", null));
    }

    @Test
    @DisplayName("should return 404 for follower and following lists of a suspended or unknown user")
    void shouldReturnNotFoundWhenListTargetSuspendedOrUnknown() {
        when(userRepository.findByUsername("gone"))
                .thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).username("gone").active(false).build()));
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        for (String username : List.of("gone", "nobody")) {
            assertThatThrownBy(() -> userService.listFollowers(username, null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
            assertThatThrownBy(() -> userService.listFollowing(username, null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        }
        org.mockito.Mockito.verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("should reject a cursor issued by another follow list")
    void shouldRejectCursorWhenIssuedByAnotherFollowList() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        com.vomatt.common.response.Cursor at = com.vomatt.common.response.Cursor.of(java.time.OffsetDateTime.now(), UUID.randomUUID());
        String mine = at.encode(com.vomatt.common.response.Cursor.Scope.FOLLOWS);
        String followers = at.encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWERS);
        String following = at.encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWING);

        for (Runnable call : List.<Runnable>of(
                () -> userService.listFollowing("alice", mine, null),
                () -> userService.listFollowing("alice", followers, null),
                () -> userService.listFollowers("alice", mine, null),
                () -> userService.listFollowers("alice", following, null))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
        }
    }
}

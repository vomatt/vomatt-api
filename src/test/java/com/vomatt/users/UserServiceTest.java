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
import com.vomatt.users.dto.FollowUserDto;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UserDto;
import com.vomatt.repository.FollowEdge;
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
    @Mock com.vomatt.votes.VoteViewRecorder voteViewRecorder;
    @Mock com.vomatt.repository.VoteBookmarkRepository bookmarkRepository;
    @Mock com.vomatt.repository.TagRepository tagRepository;
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
        when(bookmarkRepository.findByUserIdOrderByCreatedAt(id)).thenReturn(List.of(new com.vomatt.entity.VoteBookmark(user, poll)));

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
        assertThat(export.bookmarks()).extracting(b -> b.voteId()).containsExactly(poll.getId().toString());
        assertThatThrownBy(() -> userService.exportMyData(id.toString()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("should release user's Ballot from option counts and Turnout before deleting account")
    void shouldReleaseBallotCountsBeforeDeletingUser() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("u").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("user"), null));

        InOrder order = inOrder(voteOptionRepository, voteRepository, auditLogRepository, userRepository);
        order.verify(userRepository).lockForDelete(id);
        order.verify(voteRepository).decrementTurnoutForUserBallots(id);
        order.verify(voteOptionRepository).decrementForUserBallots(id);
        order.verify(auditLogRepository).anonymizeByUserId(id.toString());
        order.verify(userRepository).delete(user);
        verify(voteViewRecorder).forgetViewer(id.toString());
    }

    @Test
    void shouldRefuseWhenDeletingLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        User admin = User.builder().id(id).username("root").roles(new String[] { "user", "admin" }).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(admin));
        when(userRepository.lockActiveAdminIds()).thenReturn(List.of(id));

        assertThatThrownBy(() -> userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("admin"), null)))
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

        userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("admin"), null));

        verify(userRepository).delete(admin);
    }

    @Test
    void shouldRefuseWhenDemotedAdminDeletesOtherUserWithStaleToken() {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.isActiveAdmin(callerId)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(targetId.toString(),
                new UserPrincipal(callerId.toString(), List.of("user", "admin"), null)))
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
                new UserPrincipal(callerId.toString(), List.of("user"), null)))
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

        userService.deleteUser(targetId.toString(), new UserPrincipal(callerId.toString(), List.of("admin"), null));

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
            userService.deleteUser(id.toString(), new UserPrincipal(id.toString(), List.of("user"), null));

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

    private UserProfileProjection activeProfile(UUID id, UUID viewer) {
        UserProfileProjection projection = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getActive()).thenReturn(true);
        when(projection.getTotalPolls()).thenReturn(0L);
        when(projection.getTotalVotes()).thenReturn(0L);
        when(userRepository.findProfileForViewer("alice", viewer)).thenReturn(Optional.of(projection));
        return projection;
    }

    @Test
    @DisplayName("should report whether the viewer follows and blocked the user when signed in")
    void shouldReturnRelationFlagsWhenViewerSignedIn() {
        UUID target = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UserProfileProjection projection = activeProfile(target, viewer);
        when(projection.getFollowing()).thenReturn(true);
        when(projection.getBlocked()).thenReturn(false);
        when(projection.getFollowsViewer()).thenReturn(true);

        UserProfileResponse profile = userService.getUserProfile("alice", viewer.toString());

        assertThat(profile.id()).isEqualTo(target.toString());
        assertThat(profile.isFollowing()).isTrue();
        assertThat(profile.isBlocked()).isFalse();
        assertThat(profile.followsYou()).isTrue();
    }

    @Test
    @DisplayName("should still report that a user the caller blocked follows the caller")
    void shouldReturnFollowsYouWhenCallerBlockedFollower() {
        UUID viewer = UUID.randomUUID();
        UserProfileProjection projection = activeProfile(UUID.randomUUID(), viewer);
        when(projection.getFollowing()).thenReturn(false);
        when(projection.getBlocked()).thenReturn(true);
        when(projection.getFollowsViewer()).thenReturn(true);

        UserProfileResponse profile = userService.getUserProfile("alice", viewer.toString());

        assertThat(profile.isBlocked()).isTrue();
        assertThat(profile.isFollowing()).isFalse();
        assertThat(profile.followsYou()).isTrue();
    }

    @Test
    @DisplayName("should return follower and following counts on the public profile whether or not the caller is signed in")
    void shouldReturnFollowCountsWhenReadingProfile() {
        UserProfileProjection projection = activeProfile(UUID.randomUUID(), null);
        when(projection.getFollowersCount()).thenReturn(12L);
        when(projection.getFollowingCount()).thenReturn(8L);

        UserProfileResponse profile = userService.getUserProfile("alice", null);

        assertThat(profile.followersCount()).isEqualTo(12);
        assertThat(profile.followingCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("should return follower and following counts on the caller's own profile")
    void shouldReturnFollowCountsWhenReadingMyProfile() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(User.builder().id(id).username("alice").build()));
        UserProfileProjection projection = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(projection.getTotalPolls()).thenReturn(0L);
        when(projection.getTotalVotes()).thenReturn(0L);
        when(projection.getFollowersCount()).thenReturn(3L);
        when(projection.getFollowingCount()).thenReturn(5L);
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(projection));

        MyProfileResponse profile = userService.getMyProfile(id.toString());

        assertThat(profile.followersCount()).isEqualTo(3);
        assertThat(profile.followingCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("should return null relation flags when signed out")
    void shouldReturnNullFlagsWhenSignedOut() {
        activeProfile(UUID.randomUUID(), null);

        UserProfileResponse profile = userService.getUserProfile("alice", null);

        assertThat(profile.isFollowing()).isNull();
        assertThat(profile.isBlocked()).isNull();
        assertThat(profile.followsYou()).isNull();
    }

    @Test
    @DisplayName("should return false relation flags in the profile update response")
    void shouldReturnFalseFlagsWhenUpdatingOwnProfile() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").build();
        UserProfileProjection projection = org.mockito.Mockito.mock(UserProfileProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getTotalPolls()).thenReturn(0L);
        when(projection.getTotalVotes()).thenReturn(0L);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.findProfileByUsername("alice")).thenReturn(Optional.of(projection));

        UserProfileResponse profile = userService.updateMyProfile(id.toString(), new UpdateProfileRequest());

        assertThat(profile.isFollowing()).isFalse();
        assertThat(profile.isBlocked()).isFalse();
        assertThat(profile.followsYou()).isFalse();
    }

    @Test
    @DisplayName("should drop the caller's follow of the user when blocking them")
    void shouldUnfollowWhenBlockingFollowedUser() {
        UUID caller = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(userRepository.findById(target)).thenReturn(Optional.of(User.builder().id(target).username("t").build()));
        when(blockRepository.insertIfAbsent(any(), eq(caller), eq(target))).thenReturn(1);

        userService.blockUser(caller.toString(), target.toString());

        InOrder order = inOrder(followRepository, blockRepository);
        order.verify(followRepository).lockPair(caller, target);
        order.verify(blockRepository).insertIfAbsent(any(), eq(caller), eq(target));
        order.verify(followRepository).deleteByFollowerIdAndFollowedId(caller, target);
    }

    @Test
    @DisplayName("should reject following a user the caller blocked")
    void shouldRejectFollowWhenCallerBlockedTarget() {
        UUID caller = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(userRepository.findById(target)).thenReturn(Optional.of(User.builder().id(target).username("t").build()));
        when(blockRepository.existsByBlockerIdAndBlockedId(caller, target)).thenReturn(true);

        assertThatThrownBy(() -> userService.followUser(caller.toString(), target.toString()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_FOLLOW_BLOCKED);
                });
        // The pair lock is taken before the block check
        InOrder order = inOrder(followRepository, blockRepository);
        order.verify(followRepository).lockPair(caller, target);
        order.verify(blockRepository).existsByBlockerIdAndBlockedId(caller, target);
        verify(followRepository, never()).insertIfAbsent(any(), any(), any());
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
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findFollowersPage(eq(alice.getId()), eq(null), eq(null), any(Limit.class)))
                .thenReturn(List.of(new UserFollow(hidden, alice)));
        when(preferenceRepository.findByUserIdInAndKey(List.of(hidden.getId()), "visibility.displayName"))
                .thenReturn(List.of(pref));

        CursorResponse<FollowUserDto> page = userService.listFollowers("alice", null, null, UUID.randomUUID().toString());

        assertThat(page.items()).containsExactly(new FollowUserDto(hidden.getId().toString(), "bob", null, null, false, false));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("should list the users a user follows")
    void shouldListFollowedUsersWhenReadingFollowing() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        User bob = User.builder().id(UUID.randomUUID()).username("bob").displayName("Bob").build();
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findPage(eq(alice.getId()), eq(null), eq(null), any(Limit.class)))
                .thenReturn(List.of(new UserFollow(alice, bob)));

        CursorResponse<FollowUserDto> page = userService.listFollowing("alice", null, null, UUID.randomUUID().toString());

        assertThat(page.items()).containsExactly(new FollowUserDto(bob.getId().toString(), "bob", "Bob", null, false, false));
    }

    @Test
    @DisplayName("should return 404 for follower and following lists of a suspended or unknown user")
    void shouldReturnNotFoundWhenListTargetSuspendedOrUnknown() {
        when(userRepository.findActiveByUsername(any())).thenCallRealMethod();
        when(userRepository.findByUsername("gone"))
                .thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).username("gone").active(false).build()));
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        for (String username : List.of("gone", "nobody")) {
            assertThatThrownBy(() -> userService.listFollowers(username, null, null, UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
            assertThatThrownBy(() -> userService.listFollowing(username, null, null, UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.USER_NOT_FOUND));
        }
        org.mockito.Mockito.verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("should reject a cursor issued by another follow list or by the same list of another user")
    void shouldRejectCursorWhenIssuedByAnotherFollowList() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        UUID bob = UUID.randomUUID();
        String caller = UUID.randomUUID().toString();
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        com.vomatt.common.response.Cursor at = com.vomatt.common.response.Cursor.of(java.time.OffsetDateTime.now(), UUID.randomUUID());
        String mine = at.encode(com.vomatt.common.response.Cursor.Scope.FOLLOWS);
        String bobsFollowers = at.encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWERS, bob);
        String bobsFollowing = at.encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWING, bob);
        String alicesFollowing = at.encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWING, alice.getId());

        for (Runnable call : List.<Runnable>of(
                () -> userService.listFollowing("alice", mine, null, caller),
                () -> userService.listFollowing("alice", bobsFollowing, null, caller),
                () -> userService.listFollowers("alice", bobsFollowers, null, caller),
                () -> userService.listFollowers("alice", alicesFollowing, null, caller))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
        }
    }

    @Test
    @DisplayName("should accept the cursor a user's follower list issued for its next page")
    void shouldAcceptOwnCursorWhenPagingFollowers() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        UserFollow f1 = new UserFollow(User.builder().id(UUID.randomUUID()).username("b").build(), alice);
        UserFollow f2 = new UserFollow(User.builder().id(UUID.randomUUID()).username("c").build(), alice);
        org.springframework.test.util.ReflectionTestUtils.setField(f1, "id", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(f1, "createdAt", java.time.OffsetDateTime.now());
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findFollowersPage(eq(alice.getId()), any(), any(), any(Limit.class))).thenReturn(List.of(f1, f2));

        String caller = UUID.randomUUID().toString();
        String next = userService.listFollowers("alice", null, 1, caller).nextCursor();

        assertThat(next).isNotNull();
        userService.listFollowers("alice", next, 1, caller);
        verify(followRepository).findFollowersPage(eq(alice.getId()), eq(f1.getCreatedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC)),
                eq(f1.getId()), any(Limit.class));
    }

    @Test
    @DisplayName("should mark each listed user the caller follows and who follows the caller, from one query for the page")
    void shouldReturnRelationFlagsWhenListingFollowers() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        User mutual = User.builder().id(UUID.randomUUID()).username("bob").build();
        User fan = User.builder().id(UUID.randomUUID()).username("carol").build();
        User caller = User.builder().id(UUID.randomUUID()).username("dave").build();
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findFollowersPage(eq(alice.getId()), eq(null), eq(null), any(Limit.class)))
                .thenReturn(List.of(new UserFollow(mutual, alice), new UserFollow(fan, alice), new UserFollow(caller, alice)));
        when(followRepository.findEdgesWithViewer(caller.getId(), List.of(mutual.getId(), fan.getId(), caller.getId())))
                .thenReturn(List.of(new FollowEdge(caller.getId(), mutual.getId()), new FollowEdge(mutual.getId(), caller.getId()),
                        new FollowEdge(fan.getId(), caller.getId())));

        CursorResponse<FollowUserDto> page = userService.listFollowers("alice", null, null, caller.getId().toString());

        assertThat(page.items()).extracting(FollowUserDto::username, FollowUserDto::isFollowing, FollowUserDto::followsYou)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("bob", true, true),
                        org.assertj.core.groups.Tuple.tuple("carol", false, true),
                        org.assertj.core.groups.Tuple.tuple("dave", false, false));
        verify(followRepository, org.mockito.Mockito.times(1)).findEdgesWithViewer(any(), any());
    }

    @Test
    @DisplayName("should skip the relation query when a follow list page is empty")
    void shouldSkipRelationQueryWhenFollowListEmpty() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));

        assertThat(userService.listFollowing("alice", null, null, UUID.randomUUID().toString()).items()).isEmpty();
        verify(followRepository, never()).findEdgesWithViewer(any(), any());
    }

    @Test
    @DisplayName("should leave mutual followers null and skip their queries when signed out")
    void shouldReturnNullMutualFollowersWhenSignedOut() {
        activeProfile(UUID.randomUUID(), null);

        UserProfileResponse profile = userService.getUserProfile("alice", null);

        assertThat(profile.mutualFollowersCount()).isNull();
        assertThat(profile.mutualFollowersPreview()).isNull();
        verify(followRepository, never()).countMutualFollowers(any(), any());
        verify(followRepository, never()).findMutualFollowersPage(any(), any(), any(), any(), any(Limit.class));
    }

    @Test
    @DisplayName("should leave mutual followers null and skip their queries on the caller's own profile")
    void shouldReturnNullMutualFollowersWhenViewingOwnProfile() {
        UUID me = UUID.randomUUID();
        activeProfile(me, me);

        UserProfileResponse profile = userService.getUserProfile("alice", me.toString());

        assertThat(profile.mutualFollowersCount()).isNull();
        assertThat(profile.mutualFollowersPreview()).isNull();
        verify(followRepository, never()).countMutualFollowers(any(), any());
    }

    @Test
    @DisplayName("should return zero mutual followers and an empty preview without reading the preview when there are none")
    void shouldReturnEmptyPreviewWhenNoMutualFollowers() {
        UUID target = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        activeProfile(target, viewer);
        when(followRepository.countMutualFollowers(target, viewer)).thenReturn(0L);

        UserProfileResponse profile = userService.getUserProfile("alice", viewer.toString());

        assertThat(profile.mutualFollowersCount()).isZero();
        assertThat(profile.mutualFollowersPreview()).isEmpty();
        verify(followRepository, never()).findMutualFollowersPage(any(), any(), any(), any(), any(Limit.class));
    }

    @Test
    @DisplayName("should return the full mutual follower count with only the first 3 as the preview, display names masked")
    void shouldPreviewFirstThreeWhenMoreThanThreeMutualFollowers() {
        UUID target = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        User alice = User.builder().id(target).username("alice").build();
        activeProfile(target, viewer);
        List<User> mutuals = List.of("jtan", "mia", "ken").stream()
                .<User>map(n -> User.builder().id(UUID.randomUUID()).username(n).displayName(n.toUpperCase()).avatarUrl("bot:boba").build())
                .toList();
        when(followRepository.countMutualFollowers(target, viewer)).thenReturn(5L);
        when(followRepository.findMutualFollowersPage(target, viewer, null, null, Limit.of(3)))
                .thenReturn(mutuals.stream().map(m -> new UserFollow(m, alice)).toList());
        UserPreference hidden = new UserPreference();
        hidden.setUser(mutuals.get(1));
        hidden.setKey(VisibilityField.DISPLAY_NAME.preferenceKey());
        hidden.setValue("false");
        when(preferenceRepository.findByUserIdInAndKey(any(), eq(VisibilityField.DISPLAY_NAME.preferenceKey())))
                .thenReturn(List.of(hidden));

        UserProfileResponse profile = userService.getUserProfile("alice", viewer.toString());

        assertThat(profile.mutualFollowersCount()).isEqualTo(5);
        assertThat(profile.mutualFollowersPreview()).extracting(UserDto::username, UserDto::displayName, UserDto::avatarUrl)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("jtan", "JTAN", "bot:boba"),
                        org.assertj.core.groups.Tuple.tuple("mia", null, "bot:boba"),
                        org.assertj.core.groups.Tuple.tuple("ken", "KEN", "bot:boba"));
    }

    @Test
    @DisplayName("should return an empty mutual follower list without querying it on the caller's own profile")
    void shouldReturnEmptyListWhenListingOwnMutualFollowers() {
        User me = User.builder().id(UUID.randomUUID()).username("alice").build();
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(me));

        CursorResponse<FollowUserDto> page = userService.listMutualFollowers("alice", null, null, me.getId().toString());

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(followRepository, never()).findMutualFollowersPage(any(), any(), any(), any(), any(Limit.class));
    }

    @Test
    @DisplayName("should page mutual followers with a cursor bound to the user, rejecting a follower list cursor")
    void shouldBindCursorToUserWhenPagingMutualFollowers() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").build();
        User viewer = User.builder().id(UUID.randomUUID()).username("dave").build();
        UserFollow f1 = new UserFollow(User.builder().id(UUID.randomUUID()).username("b").build(), alice);
        UserFollow f2 = new UserFollow(User.builder().id(UUID.randomUUID()).username("c").build(), alice);
        org.springframework.test.util.ReflectionTestUtils.setField(f1, "id", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(f1, "createdAt", java.time.OffsetDateTime.now());
        when(userRepository.findActiveByUsername("alice")).thenReturn(Optional.of(alice));
        when(followRepository.findMutualFollowersPage(eq(alice.getId()), eq(viewer.getId()), any(), any(), any(Limit.class)))
                .thenReturn(List.of(f1, f2));
        when(followRepository.findEdgesWithViewer(eq(viewer.getId()), any()))
                .thenReturn(List.of(new FollowEdge(viewer.getId(), f1.getFollower().getId())));
        String caller = viewer.getId().toString();

        CursorResponse<FollowUserDto> first = userService.listMutualFollowers("alice", null, 1, caller);
        userService.listMutualFollowers("alice", first.nextCursor(), 1, caller);

        assertThat(first.items()).extracting(FollowUserDto::username, FollowUserDto::isFollowing)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("b", true));
        verify(followRepository).findMutualFollowersPage(eq(alice.getId()), eq(viewer.getId()),
                eq(f1.getCreatedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC)), eq(f1.getId()), any(Limit.class));
        String followersCursor = com.vomatt.common.response.Cursor.of(f1.getCreatedAt(), f1.getId())
                .encode(com.vomatt.common.response.Cursor.Scope.USER_FOLLOWERS, alice.getId());
        assertThatThrownBy(() -> userService.listMutualFollowers("alice", followersCursor, 1, caller))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_CURSOR_INVALID));
    }
}

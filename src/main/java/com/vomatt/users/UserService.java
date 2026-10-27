package com.vomatt.users;

import static com.vomatt.common.util.TextUtil.blankToNull;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.FollowUserDto;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.UserBlock;
import com.vomatt.entity.UserFollow;
import com.vomatt.entity.UserPreference;
import com.vomatt.repository.FollowEdge;
import com.vomatt.repository.UserBlockRepository;
import com.vomatt.repository.UserFollowRepository;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserProfileProjection;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.common.util.AfterCommit;
import com.vomatt.common.util.TextUtil;
import com.vomatt.common.util.UUIDv7Generator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UserBlockRepository blockRepository;
    private final UserFollowRepository followRepository;
    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final VoteOptionRepository voteOptionRepository;
    private final VoteRepository voteRepository;
    private final LockTimeout lockTimeout;
    private final com.vomatt.common.audit.AuditLogRepository auditLogRepository;
    private final com.vomatt.repository.UserVoteRepository userVoteRepository;
    private final com.vomatt.repository.VoteCommentRepository voteCommentRepository;
    private final com.vomatt.repository.FeedbackRepository feedbackRepository;
    private final com.vomatt.common.redis.RedisService redisService;

    /** One export per user per UTC day: the response is the account's whole footprint and is not cheap to build. */
    private static final String EXPORT_COUNTER_NS = "export:daily";

    private static final int MUTUAL_PREVIEW_SIZE = 3;

    /**
     * The user themself or an active admin can delete the account; deletion also revokes all of the user's refresh tokens.
     * Deleting someone else needs both an admin-scope token (admin console login) and an active admin in the database,
     * so neither a consumer-app session of an admin nor a demoted or suspended admin's stale token can do it.
     * <p>Rows that reference the user cascade in the database; audit_logs has no FK, so the user's entries are
     * anonymized here in the same transaction. Redis keys other than refresh tokens (OTP, rate limits, lockouts, used
     * id_tokens) carry no account data and are left to expire with their TTL.</p>
     */
    @Transactional
    public void deleteUser(String userId, UserPrincipal principal) {
        if (!principal.userId().equals(userId)
                && !(principal.grantsAdmin() && userRepository.isActiveAdmin(UUID.fromString(principal.userId())))) {
            log.warn("User {} attempted to delete user {} without permission", principal.userId(), userId);
            throw ApiException.forbidden(MessageKey.USER_DELETE_FORBIDDEN);
        }
        User user = findUserById(userId);
        requireAnotherActiveAdmin(user);
        // After commit: a sign-in or refresh before then still sees the account, and a rollback keeps its tokens
        revokeTokensAfterCommit(userId);
        // user_votes rows cascade with the user; release them from stored option counts and Turnout first.
        // Poll rows before option rows, the same lock order as a Ballot, so a concurrent Ballot cannot deadlock it
        voteRepository.decrementTurnoutForUserBallots(user.getId());
        voteOptionRepository.decrementForUserBallots(user.getId());
        voteRepository.decrementShareCountForUserShares(user.getId());
        auditLogRepository.anonymizeByUserId(userId);
        userRepository.delete(user);
        log.info("User {} deleted by {}", userId, principal.userId());
    }

    /**
     * Revokes every refresh token of the user once the transaction commits: a sign-in or refresh before then still sees
     * the old account state, and a rollback keeps the tokens. The change is already committed when this runs, so a Redis
     * failure is logged instead of turning a done suspend / role change / delete into a 500.
     */
    public void revokeTokensAfterCommit(String userId) {
        AfterCommit.run(() -> {
            try {
                refreshTokenService.revokeAllForUser(userId);
            } catch (RuntimeException e) {
                log.warn("Refresh token revocation failed after commit for userId={}: {}", userId, e.getMessage());
            }
        });
    }

    /**
     * The one last-admin guard, run before an account stops being an active admin (delete, suspend, demote): at least one
     * other active admin must remain. Locks the active admin rows first, so two admins removing each other concurrently
     * cannot both pass. A no-op when the account is not an active admin.
     */
    @Transactional
    public void requireAnotherActiveAdmin(User user) {
        if (!user.isActive() || !user.isAdmin()) {
            return;
        }
        // Bounded wait: a waiter past 3 s gets 409 common.conflict instead of holding a pooled connection
        List<UUID> activeAdmins = lockTimeout.within(userRepository::lockActiveAdminIds);
        if (activeAdmins.stream().allMatch(user.getId()::equals)) {
            throw ApiException.conflict(MessageKey.ADMIN_LAST_ADMIN);
        }
    }

    @Transactional(readOnly = true)
    public CursorResponse<UserDto> searchUsersByUsername(String username, String cursor, Integer limit) {
        log.debug("Searching users with username containing: {}", username);
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.USER_SEARCH);
        List<User> rows = userRepository.searchByUsernameAfter(username, after == null ? null : after.key(),
            Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.USER_SEARCH,
            u -> Cursor.of(u.getUsername(), u.getId()), this::toSearchResults);
    }

    /** Blocks a user (one-way, see {@link UserBlock}) and drops the caller's follow of them; idempotent. */
    @Transactional
    public void blockUser(String userId, String targetId) {
        User target = findOtherActiveUser(userId, targetId, MessageKey.USER_BLOCK_SELF);
        followRepository.lockPair(UUID.fromString(userId), target.getId());
        if (blockRepository.insertIfAbsent(UUIDv7Generator.generateId(), UUID.fromString(userId), target.getId()) > 0) {
            log.info("User {} blocked user {}", userId, targetId);
        }
        if (followRepository.deleteByFollowerIdAndFollowedId(UUID.fromString(userId), target.getId()) > 0) {
            log.info("User {} unfollowed user {} (blocked)", userId, targetId);
        }
    }

    /** Unblocks a user; idempotent, also when the user does not exist. */
    @Transactional
    public void unblockUser(String userId, String targetId) {
        if (blockRepository.deleteByBlockerIdAndBlockedId(UUID.fromString(userId), UUID.fromString(targetId)) > 0) {
            log.info("User {} unblocked user {}", userId, targetId);
        }
    }

    /** Users the caller blocked, most recently blocked first, as public profile fields. */
    @Transactional(readOnly = true)
    public CursorResponse<UserDto> listBlockedUsers(String userId, String cursor, Integer limit) {
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.BLOCKS);
        List<UserBlock> rows = blockRepository.findPage(UUID.fromString(userId),
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.BLOCKS, b -> Cursor.of(b.getCreatedAt(), b.getId()),
            page -> toSearchResults(page.stream().map(UserBlock::getBlocked).toList()));
    }

    /** Follows a user (one-way, see {@link UserFollow}); idempotent. */
    @Transactional
    public void followUser(String userId, String targetId) {
        User target = findOtherActiveUser(userId, targetId, MessageKey.USER_FOLLOW_SELF);
        // Same pair lock as blockUser, so a concurrent block cannot slip between the check and the insert
        followRepository.lockPair(UUID.fromString(userId), target.getId());
        if (blockRepository.existsByBlockerIdAndBlockedId(UUID.fromString(userId), target.getId())) {
            throw ApiException.badRequest(MessageKey.USER_FOLLOW_BLOCKED);
        }
        if (followRepository.insertIfAbsent(UUIDv7Generator.generateId(), UUID.fromString(userId), target.getId()) > 0) {
            log.info("User {} followed user {}", userId, targetId);
        }
    }

    /** Unfollows a user; idempotent, also when the user does not exist. */
    @Transactional
    public void unfollowUser(String userId, String targetId) {
        if (followRepository.deleteByFollowerIdAndFollowedId(UUID.fromString(userId), UUID.fromString(targetId)) > 0) {
            log.info("User {} unfollowed user {}", userId, targetId);
        }
    }

    /** Users the caller follows, most recently followed first, as public profile fields with the caller's relation. */
    @Transactional(readOnly = true)
    public CursorResponse<FollowUserDto> listFollowedUsers(String userId, String cursor, Integer limit) {
        UUID caller = UUID.fromString(userId);
        return followedPage(caller, Cursor.Scope.FOLLOWS, null, cursor, limit, caller);
    }

    /** Page of the users {@code followerId} follows; {@code owner} binds the cursor to one user's list (null: unbound). */
    private CursorResponse<FollowUserDto> followedPage(UUID followerId, Cursor.Scope scope, UUID owner, String cursor,
                                                       Integer limit, UUID viewerId) {
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, scope, owner);
        List<UserFollow> rows = followRepository.findPage(followerId,
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, scope, owner, f -> Cursor.of(f.getCreatedAt(), f.getId()),
            page -> toFollowItems(page.stream().map(UserFollow::getFollowed).toList(), viewerId));
    }

    /** Follow list items of a page of users: display name masking as in search, and the viewer's follows both ways in one query. */
    public List<FollowUserDto> toFollowItems(List<User> users, UUID viewerId) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> shown = displayNameSettings(users);
        boolean byDefault = VisibilityField.DISPLAY_NAME.isVisibleByDefault();
        List<FollowEdge> edges = followRepository.findEdgesWithViewer(viewerId, users.stream().map(User::getId).toList());
        Set<UUID> followedByViewer = edges.stream().filter(e -> e.followerId().equals(viewerId))
            .map(FollowEdge::followedId).collect(Collectors.toSet());
        Set<UUID> followingViewer = edges.stream().filter(e -> e.followedId().equals(viewerId))
            .map(FollowEdge::followerId).collect(Collectors.toSet());
        return users.stream().map(u -> userMapper.toFollowItem(u, shown.getOrDefault(u.getId(), byDefault),
            followedByViewer.contains(u.getId()), followingViewer.contains(u.getId()))).toList();
    }

    /** Public profile fields of a page of users, display name masked by each owner's setting. */
    public List<UserDto> toSearchResults(List<User> users) {
        Map<UUID, Boolean> shown = displayNameSettings(users);
        boolean byDefault = VisibilityField.DISPLAY_NAME.isVisibleByDefault();
        return users.stream().map(u -> userMapper.toSearchResult(u, shown.getOrDefault(u.getId(), byDefault))).toList();
    }

    /** Display name visibility explicitly set by users on the page, in one query; unset users are absent. */
    private Map<UUID, Boolean> displayNameSettings(List<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        return preferenceRepository.findByUserIdInAndKey(users.stream().map(User::getId).toList(),
                VisibilityField.DISPLAY_NAME.preferenceKey()).stream()
            .collect(Collectors.toMap(pref -> pref.getUser().getId(), pref -> Boolean.parseBoolean(pref.getValue())));
    }

    /** Users who follow the given user, most recently followed first, as public profile fields with the caller's relation. */
    @Transactional(readOnly = true)
    public CursorResponse<FollowUserDto> listFollowers(String username, String cursor, Integer limit, String viewerId) {
        UUID userId = userRepository.findActiveByUsername(username)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND)).getId();
        int size = CursorResponse.limit(limit);
        // Bound to the user, so a cursor of another user's follower list is rejected
        Cursor after = Cursor.decode(cursor, Cursor.Scope.USER_FOLLOWERS, userId);
        List<UserFollow> rows = followRepository.findFollowersPage(userId,
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.USER_FOLLOWERS, userId, f -> Cursor.of(f.getCreatedAt(), f.getId()),
            page -> toFollowItems(page.stream().map(UserFollow::getFollower).toList(), UUID.fromString(viewerId)));
    }

    /** Users the given user follows, most recently followed first, as public profile fields with the caller's relation. */
    @Transactional(readOnly = true)
    public CursorResponse<FollowUserDto> listFollowing(String username, String cursor, Integer limit, String viewerId) {
        UUID userId = userRepository.findActiveByUsername(username)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND)).getId();
        return followedPage(userId, Cursor.Scope.USER_FOLLOWING, userId, cursor, limit, UUID.fromString(viewerId));
    }

    /**
     * Followers of the given user whom the caller follows (see {@link UserFollowRepository#MUTUAL_FOLLOWER}), in follower
     * list order. Empty on the caller's own profile.
     */
    @Transactional(readOnly = true)
    public CursorResponse<FollowUserDto> listMutualFollowers(String username, String cursor, Integer limit, String viewerId) {
        UUID userId = userRepository.findActiveByUsername(username)
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND)).getId();
        UUID viewer = UUID.fromString(viewerId);
        int size = CursorResponse.limit(limit);
        Cursor after = Cursor.decode(cursor, Cursor.Scope.USER_MUTUAL_FOLLOWERS, userId);
        if (userId.equals(viewer)) {
            return new CursorResponse<>(List.of(), null);
        }
        List<UserFollow> rows = followRepository.findMutualFollowersPage(userId, viewer,
            after == null ? null : after.timeKey(), after == null ? null : after.id(), Limit.of(size + 1));
        return CursorResponse.of(rows, size, Cursor.Scope.USER_MUTUAL_FOLLOWERS, userId,
            f -> Cursor.of(f.getCreatedAt(), f.getId()),
            page -> toFollowItems(page.stream().map(UserFollow::getFollower).toList(), viewer));
    }

    /**
     * @param viewerId the signed-in caller, or null when signed out: {@code isFollowing} / {@code isBlocked} /
     *                 {@code followsYou} are then null, and false on the caller's own profile; the mutual followers
     *                 are null both when signed out and on the caller's own profile
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String username, boolean isVisibility, String viewerId) {
        // Use Projection to fetch profile and statistics in one query, avoiding N+1
        // Suspended users are hidden, same as user search and their Poll list
        boolean signedIn = viewerId != null;
        UUID viewer = signedIn ? UUID.fromString(viewerId) : null;
        UserProfileProjection projection = userRepository.findProfileForViewer(username, viewer)
            .filter(p -> Boolean.TRUE.equals(p.getActive()))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Integer mutualCount = null;
        List<UserDto> mutualPreview = null;
        if (signedIn && !projection.getId().equals(viewer)) {
            mutualCount = Math.toIntExact(followRepository.countMutualFollowers(projection.getId(), viewer));
            mutualPreview = mutualCount == 0 ? List.of() : toSearchResults(followRepository
                .findMutualFollowersPage(projection.getId(), viewer, null, null, Limit.of(MUTUAL_PREVIEW_SIZE))
                .stream().map(UserFollow::getFollower).toList());
        }

        // Query target user's visibility settings
        Map<String, Boolean> visibility = loadVisibilitySettings(projection.getId(), isVisibility);
        return userMapper.toPublicProfileResponse(projection, visibility,
                signedIn ? projection.getFollowing() : null, signedIn ? projection.getBlocked() : null,
                signedIn ? projection.getFollowsViewer() : null, mutualCount, mutualPreview);
    }

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(String userId) {
        User user = findUserById(userId);

        // Use projection to fetch statistics
        UserProfileProjection projection = userRepository.findProfileByUsername(user.getUsername())
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Map<String, Boolean> visibility = loadVisibilitySettings(UUID.fromString(userId), false);
        boolean anonymousBallotByDefault = preferenceRepository.anonymousBallotByDefault(user.getId());
        return userMapper.toMyProfileResponse(user, projection, visibility, anonymousBallotByDefault);
    }

    /** Data portability: everything stored about the caller, nothing about anyone else. Fails closed when Redis is down. */
    @Transactional(readOnly = true)
    public com.vomatt.users.dto.UserDataExportResponse exportMyData(String userId) {
        String day = TextUtil.utcDay();
        // Checked first so a spent quota costs nothing, counted only once the document is built, so a failure
        // (DB error, missing account) does not spend the day's export
        if (redisService.get(EXPORT_COUNTER_NS, userId + ":" + day, Long.class) != null) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
        UUID userUuid = UUID.fromString(userId);
        MyProfileResponse profile = getMyProfile(userId);
        Map<String, String> preferences = preferenceRepository.findByUserId(userUuid).stream()
                .collect(Collectors.toMap(UserPreference::getKey, UserPreference::getValue, (a, b) -> a, java.util.TreeMap::new));
        List<com.vomatt.users.dto.UserDataExportResponse.PollExport> polls = voteRepository.findByCreatorIdOrderByCreatedAtDesc(userUuid).stream()
                .map(v -> new com.vomatt.users.dto.UserDataExportResponse.PollExport(v.getId().toString(), v.getTitle(), v.getDescription(),
                        v.getAudience().name(), v.getStartTime(), v.getEndTime(), v.getCreatedAt(), v.getDeletedAt() != null,
                        v.getOptions().stream().sorted(java.util.Comparator.comparing(o -> o.getDisplayOrder() == null ? 0 : o.getDisplayOrder()))
                                .map(o -> o.getText()).toList()))
                .toList();
        List<com.vomatt.users.dto.UserDataExportResponse.CommentExport> comments = voteCommentRepository.findByUserId(userUuid).stream()
                .map(c -> new com.vomatt.users.dto.UserDataExportResponse.CommentExport(c.getId().toString(), c.getVote().getId().toString(),
                        c.getContent(), c.getCreatedAt()))
                .toList();
        List<com.vomatt.users.dto.UserDataExportResponse.BallotExport> ballots = userVoteRepository.findByUserIdWithVoteAndOption(userUuid).stream()
                .map(b -> new com.vomatt.users.dto.UserDataExportResponse.BallotExport(b.getVote().getId().toString(), b.getVote().getTitle(),
                        b.getOption() == null ? null : b.getOption().getId().toString(), b.getOption() == null ? null : b.getOption().getText(),
                        b.isAnonymous(), b.getCreatedAt()))
                .toList();
        List<com.vomatt.users.dto.UserDataExportResponse.FeedbackExport> feedback = feedbackRepository.findByUserIdOrderByCreatedAt(userUuid).stream()
                .map(f -> new com.vomatt.users.dto.UserDataExportResponse.FeedbackExport(f.getId().toString(), f.getContent(), f.getCreatedAt()))
                .toList();
        List<com.vomatt.users.dto.UserDataExportResponse.RelationExport> following = followRepository.findAllByFollower(userUuid).stream()
                .map(f -> new com.vomatt.users.dto.UserDataExportResponse.RelationExport(f.getFollowed().getId().toString(),
                        f.getFollowed().getUsername(), f.getCreatedAt()))
                .toList();
        List<com.vomatt.users.dto.UserDataExportResponse.RelationExport> blocking = blockRepository.findAllByBlocker(userUuid).stream()
                .map(b -> new com.vomatt.users.dto.UserDataExportResponse.RelationExport(b.getBlocked().getId().toString(),
                        b.getBlocked().getUsername(), b.getCreatedAt()))
                .toList();
        if (redisService.incrementAndExpire(EXPORT_COUNTER_NS, userId + ":" + day, java.time.Duration.ofDays(1)) > 1) {
            throw ApiException.tooManyRequests(MessageKey.COMMON_RATE_LIMITED);
        }
        log.info("Data export produced for user {}", userId);
        return new com.vomatt.users.dto.UserDataExportResponse(java.time.OffsetDateTime.now(), profile, preferences, polls, comments, ballots, feedback,
                following, blocking);
    }

    @Transactional
    public Map<String, Boolean> updateVisibility(String userId, Map<String, Boolean> visibility) {
        User user = findUserById(userId);
        UUID userUuid = user.getId();

        for (Map.Entry<String, Boolean> entry : visibility.entrySet()) {
            // Only process valid field names, ignore unknown fields
            VisibilityField.fromFieldName(entry.getKey())
                    .ifPresent(field -> savePreference(user, field.preferenceKey(), entry.getValue().toString()));
        }
        log.info("Visibility settings updated for user: {}", userId);
        return loadVisibilitySettings(userUuid, false);
    }

    private void savePreference(User user, String key, String value) {
        UserPreference pref = preferenceRepository.findByUserIdAndKey(user.getId(), key)
                .orElseGet(() -> {
                    UserPreference newPref = new UserPreference();
                    newPref.setUser(user);
                    newPref.setKey(key);
                    return newPref;
                });
        pref.setValue(value);
        preferenceRepository.save(pref);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(String userId, UpdateProfileRequest request) {
        User user = findUserById(userId);
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.getUsername())) {
                throw ApiException.conflict(MessageKey.USER_USERNAME_TAKEN);
            }
            user.setUsername(request.getUsername());
        }
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getFirstName() != null) {
            user.setFirstName(blankToNull(request.getFirstName()));
        }
        if (request.getLastName() != null) {
            user.setLastName(blankToNull(request.getLastName()));
        }
        if (request.getBirthday() != null) {
            user.setBirthday(request.getBirthday());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getAnonymousBallotByDefault() != null) {
            savePreference(user, UserPreference.ANONYMOUS_BALLOT_KEY, request.getAnonymousBallotByDefault().toString());
        }
        userRepository.save(user);
        UserProfileProjection projection = userRepository.findProfileByUsername(user.getUsername())
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
        // The owner's own profile: no follow or block of oneself exists
        return userMapper.toPublicProfileResponse(projection, loadVisibilitySettings(user.getId(), true), false, false, false,
                null, null);
    }

    /**
     * Load user's field visibility settings; unset fields fall back to their default (display name and bio public)
     */
    private Map<String, Boolean> loadVisibilitySettings(UUID userId, boolean isVisibility) {
        Map<String, Boolean> settings = Arrays.stream(VisibilityField.values())
                .collect(Collectors.toMap(VisibilityField::getFieldName,
                        f -> isVisibility || f.isVisibleByDefault()));

        // Load configured values from database
        List<UserPreference> prefs = preferenceRepository
                .findByUserIdAndKeyStartingWith(userId, VisibilityField.getPreferencePrefix());
        for (UserPreference pref : prefs) {
            String fieldName = VisibilityField.extractFieldName(pref.getKey());
            // Rows for fields that are no longer controllable (visibility.email, visibility.birthday) are ignored
            if (VisibilityField.fromFieldName(fieldName).isPresent()) {
                settings.put(fieldName, Boolean.parseBoolean(pref.getValue()));
            }
        }

        return settings;
    }

    /**
     * Target of a block or follow: a suspended account is not found, as on its profile and in user search, so these
     * endpoints do not reveal that it exists.
     */
    private User findOtherActiveUser(String userId, String targetId, MessageKey selfKey) {
        User target = findUserById(targetId);
        if (!target.isActive()) {
            throw ApiException.notFound(MessageKey.USER_NOT_FOUND);
        }
        if (target.getId().toString().equals(userId)) {
            throw ApiException.badRequest(selfKey);
        }
        return target;
    }

    private User findUserById(String userId) {
        return userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
    }
}

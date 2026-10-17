package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.RefreshTokenService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.repository.UserPreferenceRepository;
import com.vomatt.repository.UserProfileProjection;
import com.vomatt.common.response.Cursor;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.repository.LockTimeout;
import com.vomatt.common.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final VoteOptionRepository voteOptionRepository;
    private final VoteRepository voteRepository;
    private final LockTimeout lockTimeout;

    /**
     * The user themself or an active admin can delete the account; deletion also revokes all of the user's refresh tokens.
     * Admin rights are read from the database, not the token, so a demoted or suspended admin cannot use a stale token.
     */
    @Transactional
    public void deleteUser(String userId, UserPrincipal principal) {
        if (!principal.userId().equals(userId) && !userRepository.isActiveAdmin(UUID.fromString(principal.userId()))) {
            log.warn("User {} attempted to delete user {} without permission", principal.userId(), userId);
            throw ApiException.forbidden(MessageKey.USER_DELETE_FORBIDDEN);
        }
        User user = findUserById(userId);
        requireAnotherActiveAdmin(user);
        // After commit: a sign-in or refresh before then still sees the account, and a rollback keeps its tokens
        AfterCommit.run(() -> refreshTokenService.revokeAllForUser(userId));
        // user_votes rows cascade with the user; release them from stored option counts and Turnout first.
        // Poll rows before option rows, the same lock order as a Ballot, so a concurrent Ballot cannot deadlock it
        voteRepository.decrementTurnoutForUserBallots(user.getId());
        voteOptionRepository.decrementForUserBallots(user.getId());
        userRepository.delete(user);
        log.info("User {} deleted by {}", userId, principal.userId());
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
            u -> Cursor.of(u.getUsername(), u.getId()), page -> {
                Map<UUID, Boolean> shown = displayNameSettings(page);
                boolean byDefault = VisibilityField.DISPLAY_NAME.isVisibleByDefault();
                return page.stream().map(u -> userMapper.toSearchResult(u, shown.getOrDefault(u.getId(), byDefault)))
                    .toList();
            });
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

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String username, boolean isVisibility) {
        // Use Projection to fetch profile and statistics in one query, avoiding N+1
        // Suspended users are hidden, same as user search and their Poll list
        UserProfileProjection projection = userRepository.findProfileByUsername(username)
            .filter(p -> Boolean.TRUE.equals(p.getActive()))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        // Query target user's visibility settings
        Map<String, Boolean> visibility = loadVisibilitySettings(projection.getId(), isVisibility);
        return userMapper.toPublicProfileResponse(projection, visibility);
    }

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(String userId) {
        User user = findUserById(userId);

        // Use projection to fetch statistics
        UserProfileProjection projection = userRepository.findProfileByUsername(user.getUsername())
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));

        Map<String, Boolean> visibility = loadVisibilitySettings(UUID.fromString(userId), false);
        boolean anonymousBallotByDefault = preferenceRepository.anonymousBallotByDefault(user.getId());
        return userMapper.toMyProfileResponse(
                user,
                projection.getTotalPolls().intValue(),
                projection.getTotalVotes().intValue(),
                visibility,
                anonymousBallotByDefault
        );
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
        return getUserProfile(user.getUsername(), true);
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
            settings.put(fieldName, Boolean.parseBoolean(pref.getValue()));
        }

        return settings;
    }

    private User findUserById(String userId) {
        return userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> ApiException.notFound(MessageKey.USER_NOT_FOUND));
    }
}

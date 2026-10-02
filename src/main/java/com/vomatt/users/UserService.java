package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.constant.UserRole;
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
import com.vomatt.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 本人或 ADMIN 可刪除帳號；刪除同時撤銷該使用者所有 refresh token。 */
    @Transactional
    public void deleteUser(String userId, UserPrincipal principal) {
        if (!principal.userId().equals(userId) && !principal.roles().contains(UserRole.ADMIN)) {
            log.warn("User {} attempted to delete user {} without permission", principal.userId(), userId);
            throw ApiException.forbidden(MessageKey.USER_DELETE_FORBIDDEN);
        }
        User user = findUserById(userId);
        refreshTokenService.revokeAllForUser(userId);
        userRepository.delete(user);
        log.info("User {} deleted by {}", userId, principal.userId());
    }

    @Transactional(readOnly = true)
    public Page<UserDto> searchUsersByUsername(String username, Pageable pageable) {
        log.debug("Searching users with username containing: {}", username);
        return userRepository.searchByUsername(username, pageable)
            .map(userMapper::toDto);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String username, boolean isVisibility) {
        // Use Projection to fetch profile and statistics in one query, avoiding N+1
        UserProfileProjection projection = userRepository.findProfileByUsername(username)
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
        return userMapper.toMyProfileResponse(
                user,
                projection.getTotalPolls().intValue(),
                projection.getTotalVotes().intValue(),
                visibility
        );
    }

    @Transactional
    public Map<String, Boolean> updateVisibility(String userId, Map<String, Boolean> visibility) {
        User user = findUserById(userId);
        UUID userUuid = user.getId();

        for (Map.Entry<String, Boolean> entry : visibility.entrySet()) {
            // Only process valid field names, ignore unknown fields
            VisibilityField.fromFieldName(entry.getKey()).ifPresent(field -> {
                String prefKey = field.preferenceKey();
                UserPreference pref = preferenceRepository.findByUserIdAndKey(userUuid, prefKey)
                        .orElseGet(() -> {
                            UserPreference newPref = new UserPreference();
                            newPref.setUser(user);
                            newPref.setKey(prefKey);
                            return newPref;
                        });
                pref.setValue(entry.getValue().toString());
                preferenceRepository.save(pref);
            });
        }
        log.info("Visibility settings updated for user: {}", userId);
        return loadVisibilitySettings(userUuid, false);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(String userId, UpdateProfileRequest request) {
        User user = findUserById(userId);
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        userRepository.save(user);
        return getUserProfile(user.getUsername(), true);
    }

    /**
     * Load user's field visibility settings, default all controllable fields to false (hidden)
     */
    private Map<String, Boolean> loadVisibilitySettings(UUID userId, boolean isVisibility) {
        // Initialize all fields to false
        Map<String, Boolean> settings = Arrays.stream(VisibilityField.values())
                .collect(Collectors.toMap(VisibilityField::getFieldName, f -> isVisibility));

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

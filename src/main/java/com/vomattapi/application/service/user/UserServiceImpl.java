package com.vomattapi.application.service.user;

import com.vomattapi.application.dto.user.MyProfileResponse;
import com.vomattapi.application.dto.user.UpdateProfileRequest;
import com.vomattapi.application.dto.user.UserDto;
import com.vomattapi.application.dto.user.UserProfileResponse;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.mapper.UserMapper;
import com.vomattapi.application.service.user.UserService;
import com.vomattapi.application.service.user.VisibilityField;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.UserActivity;
import com.vomattapi.domain.user.UserPreference;
import com.vomattapi.domain.user.repository.UserActivityRepository;
import com.vomattapi.domain.user.repository.UserPreferenceRepository;
import com.vomattapi.domain.user.repository.UserProfileProjection;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UserActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheUtil cacheUtil;
    private final UserMapper userMapper;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int ACCOUNT_LOCK_MINUTES = 30;

    @Override
    @Transactional
    public User updateProfile(String userId, String username, String email, String phoneNumber) {
        User user = findUserById(userId);

        boolean changed = false;

        if (username != null && !username.isEmpty() && !username.equals(user.getUsername())) {
            if (userRepository.existsByUsername(username)) {
                throw new IllegalArgumentException("Username already taken");
            }
            user.setUsername(username);
            changed = true;
        }

        if (email != null && !email.isEmpty() && !email.equals(user.getEmail())) {
            if (userRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("Email already in use");
            }
            user.setEmail(email);
            changed = true;
        }

        if (phoneNumber != null && !phoneNumber.equals(user.getPhoneNumber())) {
            if (!phoneNumber.isEmpty() && userRepository.existsByPhoneNumber(phoneNumber)) {
                throw new IllegalArgumentException("Phone number already in use");
            }
            user.setPhoneNumber(phoneNumber);
            changed = true;
        }

        if (changed) {
            userRepository.save(user);
            logActivity(user, "PROFILE_UPDATED", "User profile updated");
            cacheUtil.evictUserCache(userId);
            log.debug("Evicted cache for user after profile update: {}", userId);
        }

        return user;
    }

    @Override
    @Transactional
    public boolean changePassword(String userId, String currentPassword, String newPassword) {
        User user = findUserById(userId);

        if (!passwordEncoder.matches(currentPassword, user.getCredential())) {
            logActivity(user, "PASSWORD_CHANGE_FAILED", "Invalid current password");
            return false;
        }

        user.setCredential(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logActivity(user, "PASSWORD_CHANGED", "Password changed successfully");
        cacheUtil.evictUserCache(userId);
        log.debug("Evicted cache for user after password change: {}", userId);

        return true;
    }

    @Override
    @Transactional
    public void handleFailedLogin(String username) {
        userRepository.findByUsername(username).ifPresent(member -> {
            member.incrementLoginAttempts();
            if (member.getLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                member.lockAccount(ACCOUNT_LOCK_MINUTES);
                logActivity(member, "ACCOUNT_LOCKED", "Account locked due to multiple failed login attempts");
                cacheUtil.evictUserCache(member.getId().toString());
                log.debug("Evicted cache for locked user: {}", member.getId());
            }
            userRepository.save(member);
        });
    }

    @Override
    @Transactional
    public void recordLogin(String userId, String ipAddress, String userAgent) {
        User user = findUserById(userId);
        user.recordLogin();
        userRepository.save(user);

        logActivity(user, "LOGIN", "Successful login", ipAddress, userAgent);
        cacheUtil.evictUserCache(userId);
        log.debug("Evicted cache for user after successful login: {}", userId);
    }

    @Override
    public User getUserById(String userId) {
        return cacheUtil.cacheUserData(userId, CacheUtil.CacheKeys.USER_PROFILE, User.class,
            () -> findUserById(userId));
    }

    @Override
    public User getUserByEmail(String email) {
        String cacheKey = "user:email:" + email;
        return cacheUtil.getOrSet(cacheKey, User.class,
            () -> userRepository.findByEmail(email).orElse(null));
    }

    @Override
    public User getUserByUsername(String username) {
        String cacheKey = "user:username:" + username;
        return cacheUtil.getOrSet(cacheKey, User.class,
            () -> userRepository.findByUsername(username).orElse(null));
    }

    /**
     * Update verification code to DB after encryption, for login purposes (OTP stored as BCrypt hash)
     */
    @Override
    @Transactional
    public boolean changeVerificationCode(String email, String verificationCode) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.warn("User not found for email: {}", email);
            return false;
        }

        user.setCredential(passwordEncoder.encode(verificationCode));
        userRepository.save(user);
        logActivity(user, "VERIFICATION_CODE_CHANGED", "Verification code updated");

        cacheUtil.evictUserCache(user.getId().toString());
        cacheUtil.evict("user:email:" + email);
        log.debug("Evicted cache for user after verification code change: {}", user.getId());

        return true;
    }

    @Override
    @Transactional
    public void deleteUser(String userId) {
        User user = findUserById(userId);

        logActivity(user, "ACCOUNT_DELETED", "User account deleted");

        cacheUtil.evictUserCache(userId);
        cacheUtil.evict("user:email:" + user.getEmail());
        cacheUtil.evict("user:username:" + user.getUsername());

        log.info("Deleting user account: {}", userId);
        userRepository.delete(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> searchUsersByUsername(String username, Pageable pageable) {
        log.debug("Searching users with username containing: {}", username);
        return userRepository.searchByUsername(username, pageable)
            .map(userMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String username, boolean isVisibility) {
        // Use Projection to fetch profile and statistics in one query, avoiding N+1
        UserProfileProjection projection = userRepository.findProfileByUsername(username)
            .orElseThrow(() -> new EntityNotFoundException("User", username));

        // Query target user's visibility settings
        Map<String, Boolean> visibility = loadVisibilitySettings(projection.getId(), isVisibility);
        return userMapper.toPublicProfileResponse(projection, visibility);
    }

    @Override
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(String userId) {
        User user = findUserById(userId);

        // Use projection to fetch statistics
        UserProfileProjection projection = userRepository.findProfileByUsername(user.getUsername())
            .orElseThrow(() -> new EntityNotFoundException("User", userId));

        Map<String, Boolean> visibility = loadVisibilitySettings(UUID.fromString(userId), false);
        return userMapper.toMyProfileResponse(
                user,
                projection.getTotalPolls().intValue(),
                projection.getTotalVotes().intValue(),
                visibility
        );
    }

    @Override
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

        cacheUtil.evictUserCache(userId);
        log.info("Visibility settings updated for user: {}", userId);
        return loadVisibilitySettings(userUuid, false);
    }

    @Override
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
        cacheUtil.evictUserCache(userId);
        log.debug("Evicted cache for user after profile update: {}", userId);
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
            .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    private void logActivity(User user, String activityType, String description) {
        logActivity(user, activityType, description, null, null);
    }

    private void logActivity(User user, String activityType, String description,
                              String ipAddress, String userAgent) {
        UserActivity activity = new UserActivity(user, activityType, description, ipAddress, userAgent);
        activityRepository.save(activity);
    }
}

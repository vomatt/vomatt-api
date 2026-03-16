package com.vomattapi.application.service.impl;

import com.vomattapi.application.dto.request.UpdateProfileRequest;
import com.vomattapi.application.dto.response.UserDto;
import com.vomattapi.application.dto.response.UserProfileResponse;
import com.vomattapi.application.mapper.UserMapper;
import com.vomattapi.application.service.UserService;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.UserActivity;
import com.vomattapi.domain.user.repository.UserActivityRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.repository.UserVoteRepository;
import com.vomattapi.domain.vote.repository.VoteRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheUtil cacheUtil;
    private final UserMapper userMapper;
    private final VoteRepository voteRepository;
    private final UserVoteRepository userVoteRepository;

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

        if (!passwordEncoder.matches(currentPassword, user.getVerificationCode())) {
            logActivity(user, "PASSWORD_CHANGE_FAILED", "Invalid current password");
            return false;
        }

        user.setVerificationCode(passwordEncoder.encode(newPassword));
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
     * 將驗證碼加密後更新至 DB，用於登入用途（OTP 轉存為 BCrypt hash）
     */
    @Override
    @Transactional
    public boolean changeVerificationCode(String email, String verificationCode) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.warn("User not found for email: {}", email);
            return false;
        }

        user.setVerificationCode(passwordEncoder.encode(verificationCode));
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
    public UserProfileResponse getUserProfile(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found: " + username));
        int totalPolls = (int) voteRepository.countByCreatorId(user.getId());
        int totalVotes = (int) userVoteRepository.countDistinctVoteByUserId(user.getId());
        return new UserProfileResponse(
            user.getUsername(),
            user.getDisplayName(),
            user.getBio(),
            user.getCreatedAt(),
            totalPolls,
            totalVotes
        );
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
        return getUserProfile(user.getUsername());
    }

    private User findUserById(String userId) {
        return userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
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

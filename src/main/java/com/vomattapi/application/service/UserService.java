package com.vomattapi.application.service;

import com.vomattapi.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.domain.user.UserActivity;
import com.vomattapi.domain.user.repository.UserActivityRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import com.vomattapi.infrastructure.redis.RedisService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final UserActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisService redisService;
    private final CacheUtil cacheUtil;

    private static final int VERIFICATION_CODE_EXPIRY_MINUTES = 15;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int ACCOUNT_LOCK_MINUTES = 30;

    /**
     * Update member profile information
     */
    @Transactional
    public User updateProfile(String memberId, String username, String email, String phoneNumber) {
        User user = findUserById(memberId);
        
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

            // 清除用戶緩存
            cacheUtil.evictUserCache(memberId);
            log.debug("Evicted cache for user after profile update: {}", memberId);
        }
        
        return user;
    }

    /**
     * Change member password
     */
    @Transactional
    public boolean changePassword(String memberId, String currentPassword, String newPassword) {
        User user = findUserById(memberId);
        
        if (passwordEncoder.matches(currentPassword, user.getVerificationCode())) {
            user.setVerificationCode(passwordEncoder.encode(newPassword));
            userRepository.save(user);
            
            logActivity(user, "PASSWORD_CHANGED", "Password changed successfully");

            // 清除用戶緩存
            cacheUtil.evictUserCache(memberId);
            log.debug("Evicted cache for user after password change: {}", memberId);

            return true;
        } else {
            logActivity(user, "PASSWORD_CHANGE_FAILED", "Invalid current password");
            return false;
        }
    }

    /**
     * Handle failed login attempt
     */
    @Transactional
    public void handleFailedLogin(String username) {
        userRepository.findByUsername(username).ifPresent(member -> {
            member.incrementLoginAttempts();
            if (member.getLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                member.lockAccount(ACCOUNT_LOCK_MINUTES);
                logActivity(member, "ACCOUNT_LOCKED", "Account locked due to multiple failed login attempts");

                // 清除用戶緩存
                cacheUtil.evictUserCache(member.getId());
                log.debug("Evicted cache for locked user: {}", member.getId());
            }
            userRepository.save(member);
        });
    }

    /**
     * Record successful login
     */
    @Transactional
    public void recordLogin(String memberId, String ipAddress, String userAgent) {
        User user = findUserById(memberId);
        user.recordLogin();
        userRepository.save(user);
        
        logActivity(user, "LOGIN", "Successful login", ipAddress, userAgent);

        // 清除用戶緩存以確保最新的登錄信息
        cacheUtil.evictUserCache(memberId);
        log.debug("Evicted cache for user after successful login: {}", memberId);
    }

    /**
     * Get member by ID with caching
     */
    public User getUserById(String memberId) {
        return cacheUtil.cacheUserData(memberId, CacheUtil.CacheKeys.USER_PROFILE, User.class,
            () -> findUserById(memberId));
    }

    /**
     * Get member by email with caching
     */
    public User getUserByEmail(String email) {
        String cacheKey = "member:email:" + email;
        return cacheUtil.getOrSet(cacheKey, User.class,
            () -> userRepository.findByEmail(email).orElse(null));
    }

    /**
     * Get member by username with caching
     */
    public User getUserByUsername(String username) {
        String cacheKey = "member:username:" + username;
        return cacheUtil.getOrSet(cacheKey, User.class,
            () -> userRepository.findByUsername(username).orElse(null));
    }

    /**
     * Helper method to find member by ID or throw exception
     */
    private User findUserById(String memberId) {
        return userRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + memberId));
    }

    /**
     * Log member activity
     */
    private void logActivity(User user, String activityType, String description) {
        logActivity(user, activityType, description, null, null);
    }

    /**
     * Log member activity with IP and user agent
     */
    private void logActivity(User user, String activityType, String description, String ipAddress, String userAgent) {
        UserActivity activity = new UserActivity(user, activityType, description, ipAddress, userAgent);
        activityRepository.save(activity);
    }

    /**
     * Change member password
     */
    @Transactional
    public boolean changeVerificationCode(String email, String verificationCode) {
        User user = findUserByEmail(email);
        if (user == null) {
            return false;
        }

        user.setVerificationCode(passwordEncoder.encode(verificationCode));
        userRepository.save(user);
        logActivity(user, "VERIFY_CODE_CHANGED", "Verification Code changed successfully");

        // 清除用戶緩存
        cacheUtil.evictUserCache(user.getId());
        // 也清除按email查詢的緩存
        String emailCacheKey = "member:email:" + email;
        cacheUtil.evict(emailCacheKey);
        log.debug("Evicted cache for user after verify code change: {}", user.getId());

        return true;
    }

    /**
     * Helper method to find member by ID or throw exception
     */
    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email).orElse(null);
    }


}
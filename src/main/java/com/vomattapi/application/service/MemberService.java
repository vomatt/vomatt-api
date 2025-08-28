package com.vomattapi.application.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.MemberActivity;
import com.vomattapi.domain.member.repository.MemberActivityRepository;
import com.vomattapi.domain.member.repository.MemberRepository;
import com.vomattapi.infrastructure.redis.CacheUtil;
import com.vomattapi.infrastructure.redis.RedisService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MemberService {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberActivityRepository activityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RedisService redisService;

    @Autowired
    private CacheUtil cacheUtil;

    private static final int VERIFICATION_CODE_EXPIRY_MINUTES = 15;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int ACCOUNT_LOCK_MINUTES = 30;

    /**
     * Update member profile information
     */
    @Transactional
    public Member updateProfile(String memberId, String username, String email, String phoneNumber) {
        Member member = findMemberById(memberId);
        
        boolean changed = false;
        
        if (username != null && !username.isEmpty() && !username.equals(member.getUsername())) {
            if (memberRepository.existsByUsername(username)) {
                throw new IllegalArgumentException("Username already taken");
            }
            member.setUsername(username);
            changed = true;
        }
        
        if (email != null && !email.isEmpty() && !email.equals(member.getEmail())) {
            if (memberRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("Email already in use");
            }
            member.setEmail(email);
            changed = true;
        }
        
        if (phoneNumber != null && !phoneNumber.equals(member.getPhoneNumber())) {
            if (!phoneNumber.isEmpty() && memberRepository.existsByPhoneNumber(phoneNumber)) {
                throw new IllegalArgumentException("Phone number already in use");
            }
            member.setPhoneNumber(phoneNumber);
            changed = true;
        }
        
        if (changed) {
            memberRepository.save(member);
            logActivity(member, "PROFILE_UPDATED", "Member profile updated");

            // 清除用戶緩存
            cacheUtil.evictUserCache(memberId);
            log.debug("Evicted cache for user after profile update: {}", memberId);
        }
        
        return member;
    }

    /**
     * Change member password
     */
    @Transactional
    public boolean changePassword(String memberId, String currentPassword, String newPassword) {
        Member member = findMemberById(memberId);
        
        if (passwordEncoder.matches(currentPassword, member.getVerifyCode())) {
            member.setVerifyCode(passwordEncoder.encode(newPassword));
            memberRepository.save(member);
            
            logActivity(member, "PASSWORD_CHANGED", "Password changed successfully");

            // 清除用戶緩存
            cacheUtil.evictUserCache(memberId);
            log.debug("Evicted cache for user after password change: {}", memberId);

            return true;
        } else {
            logActivity(member, "PASSWORD_CHANGE_FAILED", "Invalid current password");
            return false;
        }
    }

    /**
     * Handle failed login attempt
     */
    @Transactional
    public void handleFailedLogin(String username) {
        memberRepository.findByUsername(username).ifPresent(member -> {
            member.incrementLoginAttempts();
            if (member.getLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                member.lockAccount(ACCOUNT_LOCK_MINUTES);
                logActivity(member, "ACCOUNT_LOCKED", "Account locked due to multiple failed login attempts");

                // 清除用戶緩存
                cacheUtil.evictUserCache(member.getId());
                log.debug("Evicted cache for locked user: {}", member.getId());
            }
            memberRepository.save(member);
        });
    }

    /**
     * Record successful login
     */
    @Transactional
    public void recordLogin(String memberId, String ipAddress, String userAgent) {
        Member member = findMemberById(memberId);
        member.recordLogin();
        memberRepository.save(member);
        
        logActivity(member, "LOGIN", "Successful login", ipAddress, userAgent);

        // 清除用戶緩存以確保最新的登錄信息
        cacheUtil.evictUserCache(memberId);
        log.debug("Evicted cache for user after successful login: {}", memberId);
    }

    /**
     * Get member by ID with caching
     */
    public Member getMemberById(String memberId) {
        return cacheUtil.cacheUserData(memberId, CacheUtil.CacheKeys.USER_PROFILE, Member.class,
            () -> findMemberById(memberId));
    }

    /**
     * Get member by email with caching
     */
    public Member getMemberByEmail(String email) {
        String cacheKey = "member:email:" + email;
        return cacheUtil.getOrSet(cacheKey, Member.class,
            () -> memberRepository.findByEmail(email).orElse(null));
    }

    /**
     * Get member by username with caching
     */
    public Member getMemberByUsername(String username) {
        String cacheKey = "member:username:" + username;
        return cacheUtil.getOrSet(cacheKey, Member.class,
            () -> memberRepository.findByUsername(username).orElse(null));
    }

    /**
     * Helper method to find member by ID or throw exception
     */
    private Member findMemberById(String memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found with id: " + memberId));
    }

    /**
     * Log member activity
     */
    private void logActivity(Member member, String activityType, String description) {
        logActivity(member, activityType, description, null, null);
    }

    /**
     * Log member activity with IP and user agent
     */
    private void logActivity(Member member, String activityType, String description, String ipAddress, String userAgent) {
        MemberActivity activity = new MemberActivity(member, activityType, description, ipAddress, userAgent);
        activityRepository.save(activity);
    }

    /**
     * Change member password
     */
    @Transactional
    public boolean changeVerifyCode(String email, String verifyCode) {
        Member member = findMemberByEmail(email);
        if (member == null) {
            return false;
        }

        member.setVerifyCode(passwordEncoder.encode(verifyCode));
        memberRepository.save(member);
        logActivity(member, "VERIFY_CODE_CHANGED", "Verify Code changed successfully");

        // 清除用戶緩存
        cacheUtil.evictUserCache(member.getId());
        // 也清除按email查詢的緩存
        String emailCacheKey = "member:email:" + email;
        cacheUtil.evict(emailCacheKey);
        log.debug("Evicted cache for user after verify code change: {}", member.getId());

        return true;
    }

    /**
     * Helper method to find member by ID or throw exception
     */
    private Member findMemberByEmail(String email) {
        return memberRepository.findByEmail(email).orElse(null);
    }
}
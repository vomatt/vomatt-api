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

@Service
public class MemberService {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberActivityRepository activityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final int VERIFICATION_CODE_EXPIRY_MINUTES = 15;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int ACCOUNT_LOCK_MINUTES = 30;

    /**
     * Reset password using token
     */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<Member> memberOpt = memberRepository.findAll().stream()
                .filter(m -> token.equals(m.getVerificationCode()) && 
                       m.getVerificationCodeExpiry() != null && 
                       m.getVerificationCodeExpiry().isAfter(LocalDateTime.now()))
                .findFirst();
        
        if (memberOpt.isPresent()) {
            Member member = memberOpt.get();
            member.setVerifyCode(passwordEncoder.encode(newPassword));
            member.clearVerificationCode();
            member.unlockAccount();
            memberRepository.save(member);
            
            logActivity(member, "PASSWORD_RESET_COMPLETED", "Password reset completed successfully");
            return true;
        }
        return false;
    }

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
    }

    /**
     * Get member by ID
     */
    public Member getMemberById(String memberId) {
        return findMemberById(memberId);
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
        return true;
    }

    /**
     * Helper method to find member by ID or throw exception
     */
    private Member findMemberByEmail(String email) {
        return memberRepository.findByEmail(email).orElse(null);
    }
}
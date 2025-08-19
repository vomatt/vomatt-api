package com.vomattapi.domain.member;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "members",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "username"),
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "phone_number")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"roles", "preferences"})
@EqualsAndHashCode(of = "id")
public class Member {
    @Id
    private String id;

    @NotBlank
    @Size(max = 50)
    private String username;

    @NotBlank
    @Size(max = 50)
    @Email
    private String email;

    @Size(max = 20)
    @Column(name = "phone_number")
    private String phoneNumber;

    @NotBlank
    @Size(max = 120)
    private String password;

    @Column(name = "email_verified")
    private boolean emailVerified = true;

    @Column(name = "phone_verified")
    private boolean phoneVerified = true;

    @Column(name = "created_at")
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "login_attempts")
    private int loginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "verification_code")
    private String verificationCode;

    @Column(name = "verification_code_expiry")
    private LocalDateTime verificationCodeExpiry;

    private int points = 0;

    @Column(name = "membership_level")
    private String membershipLevel = "BASIC";

    private boolean active = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "member_roles",
            joinColumns = @JoinColumn(name = "member_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MemberPreference> preferences = new HashSet<>();

    // Constructor for creating a new member
    public Member(String username, String email, String password) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.email = email;
        this.password = password;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    // Constructor for creating a new member with phone number
    public Member(String username, String email, String phoneNumber, String password) {
        this(username, email, password);
        this.phoneNumber = phoneNumber;
    }

    // Methods for account management
    public void lockAccount(int minutes) {
        this.lockedUntil = LocalDateTime.now().plusMinutes(minutes);
    }

    public void unlockAccount() {
        this.lockedUntil = null;
        this.loginAttempts = 0;
    }

    public boolean isAccountLocked() {
        return this.lockedUntil != null && LocalDateTime.now().isBefore(this.lockedUntil);
    }

    public void incrementLoginAttempts() {
        this.loginAttempts++;
    }

    public void resetLoginAttempts() {
        this.loginAttempts = 0;
    }

    public void recordLogin() {
        this.lastLoginAt = LocalDateTime.now();
        this.loginAttempts = 0;
    }

    // Methods for verification (simplified)
    public void setVerificationCode(String code, int expiryMinutes) {
        this.verificationCode = code;
        this.verificationCodeExpiry = LocalDateTime.now().plusMinutes(expiryMinutes);
    }

    public boolean isVerificationCodeValid(String code) {
        return this.verificationCode != null && 
               this.verificationCode.equals(code) && 
               LocalDateTime.now().isBefore(this.verificationCodeExpiry);
    }

    public void clearVerificationCode() {
        this.verificationCode = null;
        this.verificationCodeExpiry = null;
    }

    // Methods for membership management
    public void addPoints(int points) {
        this.points += points;
        updateMembershipLevel();
    }

    public void deductPoints(int points) {
        this.points = Math.max(0, this.points - points);
        updateMembershipLevel();
    }

    private void updateMembershipLevel() {
        if (points >= 10000) {
            this.membershipLevel = "PLATINUM";
        } else if (points >= 5000) {
            this.membershipLevel = "GOLD";
        } else if (points >= 1000) {
            this.membershipLevel = "SILVER";
        } else {
            this.membershipLevel = "BASIC";
        }
    }
}

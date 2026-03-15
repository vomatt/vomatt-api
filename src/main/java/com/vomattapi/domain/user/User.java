package com.vomattapi.domain.user;

import com.vomattapi.domain.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "username"),
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "phone_number")
        })
@NoArgsConstructor
@AllArgsConstructor
@Data
public class User extends BaseEntity {

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

    @Size(max = 120)
    @Column(name = "verification_code")
    private String verificationCode;

    @Column(name = "verification_code_expiry")
    private LocalDateTime verificationCodeExpiry;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "login_attempts")
    private int loginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    private int points = 0;

    @Column(name = "membership_level")
    private String membershipLevel = "BASIC";

    private boolean active = true;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "location")
    private String location;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserPreference> preferences = new HashSet<>();

    // Constructor for creating a new member
    public User(String username, String email, String verificationCode) {
        this.username = username;
        this.email = email;
        this.verificationCode = verificationCode;
    }

    // Constructor for creating a new member with phone number
    public User(String username, String email, String phoneNumber, String verificationCode, String firstName, String lastName) {
        this(username, email, verificationCode);
        this.phoneNumber = phoneNumber;
        this.firstName = firstName;
        this.lastName = lastName;
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

    // Methods for membership management
    public void addPoints(int points) {
        this.points += points;
        updateUsershipLevel();
    }

    public void deductPoints(int points) {
        this.points = Math.max(0, this.points - points);
        updateUsershipLevel();
    }

    private void updateUsershipLevel() {
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

package com.vomatt.entity;

import com.vomatt.common.constant.UserRole;
import com.vomatt.entity.common.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 平台使用者。登入方式為 Email/手機 OTP 或 Google / LINE / Apple OAuth，不保存密碼。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class User extends AuditableEntity {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    /** 手機 OTP 註冊者可為 null。 */
    @Column(name = "email", unique = true, length = 254)
    private String email;

    @Column(name = "phone_number", unique = true, length = 20)
    private String phoneNumber;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", nullable = false, columnDefinition = "text[]")
    private String[] roles = new String[] { UserRole.USER };

    /** email_otp / phone_otp / google / line / apple */
    @Column(name = "auth_method", length = 20)
    private String authMethod;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Builder.Default
    @Column(name = "points", nullable = false)
    private int points = 0;

    @Builder.Default
    @Column(name = "membership_level", nullable = false, length = 20)
    private String membershipLevel = "BASIC";

    /** false 即停權：所有登入路徑與 refresh rotation 皆拒絕。 */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "first_name", length = 50)
    private String firstName;

    @Column(name = "last_name", length = 50)
    private String lastName;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Builder.Default
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserPreference> preferences = new HashSet<>();

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

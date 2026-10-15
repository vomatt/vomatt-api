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
import java.util.Locale;
import java.util.Set;

/**
 * Platform user. Signs in via Email/phone OTP or Google / LINE / Apple OAuth; no password is stored.
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

    /** Null for users who registered via phone OTP. Stored as {@link #normalizeEmail}. */
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

    /** false means suspended: all login paths and refresh rotation are rejected. */
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

    /**
     * The one email normalization: sign-up / sign-in lookups and email Invitees must agree on it. Trims and lower-cases;
     * a Gmail address (gmail.com / googlemail.com) also loses its {@code +tag} and dots and moves to gmail.com, since
     * all its variants reach one inbox. db/migrations/2026-10-14-canonical-gmail.sql applies the same rule.
     */
    public static String normalizeEmail(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        String domain = normalized.substring(at + 1);
        if (at < 0 || !domain.equals("gmail.com") && !domain.equals("googlemail.com")) {
            return normalized;
        }
        String local = normalized.substring(0, at);
        int plus = local.indexOf('+');
        return (plus < 0 ? local : local.substring(0, plus)).replace(".", "") + "@gmail.com";
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

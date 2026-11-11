package com.vomatt.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.vomatt.entity.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An invitation to join a community account, sent to a normalized email address with the role the invitee gets. Never
 * updated: accepting, declining or joining at sign-up deletes it, and inviting the address again replaces an expired one.
 */
@Entity
@Table(name = "community_invitations")
@Getter
@NoArgsConstructor
public class CommunityInvitation extends BaseEntity {

    @Column(name = "community_user_id", nullable = false)
    private UUID communityUserId;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private CommunityRole role;

    /** The real member who invited; null once their account is deleted. */
    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public CommunityInvitation(UUID communityUserId, String email, CommunityRole role, UUID invitedBy, OffsetDateTime expiresAt) {
        this.communityUserId = communityUserId;
        this.email = email;
        this.role = role;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }
}

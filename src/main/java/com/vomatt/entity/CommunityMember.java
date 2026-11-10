package com.vomatt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A person who manages a community account, with one {@link CommunityRole}. */
@Entity
@Table(name = "community_members")
@IdClass(CommunityMember.Key.class)
@Getter
@NoArgsConstructor
public class CommunityMember {

    @Id
    @Column(name = "community_user_id")
    private UUID communityUserId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private CommunityRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Read-only views of the two users, for the member list and the account switcher
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_user_id", insertable = false, updatable = false)
    private User community;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    public CommunityMember(UUID communityUserId, UUID userId, CommunityRole role) {
        this.communityUserId = communityUserId;
        this.userId = userId;
        this.role = role;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID communityUserId;
        private UUID userId;
    }
}

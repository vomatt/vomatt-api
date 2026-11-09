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

/** A person who manages an official account, with one {@link OfficialRole}. */
@Entity
@Table(name = "official_members")
@IdClass(OfficialMember.Key.class)
@Getter
@NoArgsConstructor
public class OfficialMember {

    @Id
    @Column(name = "official_user_id")
    private UUID officialUserId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private OfficialRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Read-only views of the two users, for the member list and the account switcher
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "official_user_id", insertable = false, updatable = false)
    private User official;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    public OfficialMember(UUID officialUserId, UUID userId, OfficialRole role) {
        this.officialUserId = officialUserId;
        this.userId = userId;
        this.role = role;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID officialUserId;
        private UUID userId;
    }
}

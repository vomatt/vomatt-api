package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A reusable list of users and email addresses its owner keeps privately for inviting. Inviting a Group copies its
 * current members into a Poll's Invitees; later changes to the Group do not touch existing Polls.
 */
// GROUP is a JPQL keyword, so the entity gets another name in queries
@Entity(name = "InviteGroup")
@Table(name = "groups")
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "owner")
public class Group extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    public Group(User owner, String name) {
        this.owner = owner;
        this.name = name.strip();
    }
}

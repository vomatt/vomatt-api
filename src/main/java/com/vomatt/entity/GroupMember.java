package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A member of a Group, shaped like {@link Invitee}: added by ID it holds the user; added by email it keeps that
 * normalized email for life and also holds the user once the address belongs to an active account (at add time, or
 * when someone verifies that email). An email member is always shown by its address.
 */
@Entity
@Table(name = "group_members", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"group_id", "user_id"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"group", "user"})
public class GroupMember extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "email", length = 254)
    private String email;

    public GroupMember(Group group, User user) {
        this(group, user, null);
    }

    public GroupMember(Group group, String email) {
        this(group, null, email);
    }
}

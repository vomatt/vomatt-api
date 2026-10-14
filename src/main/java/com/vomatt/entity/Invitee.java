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
 * Someone the owner of an Invite-only Poll allowed to see it and cast a Ballot in it. Added by ID it holds the user;
 * added by email it keeps that normalized email for life and also holds the user once the address belongs to an
 * active account (at invite time, or when someone verifies that email). An email row is always shown by its address.
 */
@Entity
@Table(name = "vote_invitees", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"vote_id", "user_id"})
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"vote", "user"})
public class Invitee extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "email", length = 254)
    private String email;

    public Invitee(Vote vote, User user) {
        this(vote, user, null);
    }

    public Invitee(Vote vote, String email) {
        this(vote, null, email);
    }
}

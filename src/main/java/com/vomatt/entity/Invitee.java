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
 * A user, or a normalized email not yet tied to a user (exactly one is set), the owner of an Invite-only Poll
 * allowed to see it and cast a Ballot in it. An email row becomes a user row once someone verifies that email.
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

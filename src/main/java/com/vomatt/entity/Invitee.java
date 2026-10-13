package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
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

/** A user the owner of an Invite-only Poll allowed to see it and cast a Ballot in it. */
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

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
}

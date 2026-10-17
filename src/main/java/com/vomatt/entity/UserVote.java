package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
import com.vomatt.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "user_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "vote_id"})
})
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = {"user", "vote", "option"})
public class UserVote extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private VoteOption option;

    @Column(name = "ip_address")
    private String ipAddress;

    /** Anonymous Ballot: left out of the voter list whatever the Poll's Voter Visibility. */
    @Column(name = "anonymous")
    private boolean anonymous;

    public UserVote(User user, Vote vote, VoteOption option, String ipAddress) {
        this.user = user;
        this.vote = vote;
        this.option = option;
        this.ipAddress = ipAddress;
    }
}

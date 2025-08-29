package com.vomattapi.domain.vote;

import com.vomattapi.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "vote_id", "option_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote", "option"})
@EqualsAndHashCode(of = "id")
public class UserVote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "voted_at")
    @CreationTimestamp
    private LocalDateTime votedAt;

    @Column(name = "ip_address")
    private String ipAddress;

    public UserVote(User user, Vote vote, VoteOption option) {
        this.user = user;
        this.vote = vote;
        this.option = option;
        this.votedAt = LocalDateTime.now();
    }

    public UserVote(User user, Vote vote, VoteOption option, String ipAddress) {
        this(user, vote, option);
        this.ipAddress = ipAddress;
    }
}
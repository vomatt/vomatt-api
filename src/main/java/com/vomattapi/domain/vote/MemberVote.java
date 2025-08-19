package com.vomattapi.domain.vote;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.vomattapi.domain.member.Member;

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

@Entity
@Table(name = "member_votes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"member_id", "vote_id", "option_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"member", "vote", "option"})
@EqualsAndHashCode(of = "id")
public class MemberVote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

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

    public MemberVote(Member member, Vote vote, VoteOption option) {
        this.member = member;
        this.vote = vote;
        this.option = option;
        this.votedAt = LocalDateTime.now();
    }

    public MemberVote(Member member, Vote vote, VoteOption option, String ipAddress) {
        this(member, vote, option);
        this.ipAddress = ipAddress;
    }
}
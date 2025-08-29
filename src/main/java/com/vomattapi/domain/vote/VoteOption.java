package com.vomattapi.domain.vote;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "vote_options")
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"vote", "memberVotes"})
@EqualsAndHashCode(of = "id")
public class VoteOption {
    @Id
    private String id;

    @NotBlank
    @Size(max = 200)
    private String text;

    @Size(max = 500)
    private String description;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(name = "created_at")
    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "option", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserVote> userVotes = new HashSet<>();

    public VoteOption(String text, String description, Vote vote) {
        this.id = UUID.randomUUID().toString();
        this.text = text;
        this.description = description;
        this.vote = vote;
        this.createdAt = LocalDateTime.now();
    }

    public VoteOption(String text, Vote vote) {
        this(text, null, vote);
    }

    public long getVoteCount() {
        return userVotes.size();
    }
}
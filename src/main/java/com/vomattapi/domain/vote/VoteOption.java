package com.vomattapi.domain.vote;

import java.util.HashSet;
import java.util.Set;

import com.vomattapi.domain.common.BaseEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "vote_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"vote", "memberVotes"})
public class VoteOption extends BaseEntity {

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

    @Size(max = 500)
    @Column(name = "image_url")
    private String imageUrl;

    @OneToMany(mappedBy = "option", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserVote> userVotes = new HashSet<>();

    public VoteOption(String text, String description, Vote vote) {
        this.text = text;
        this.description = description;
        this.vote = vote;
    }

    public long getVoteCount() {
        return userVotes.size();
    }
}

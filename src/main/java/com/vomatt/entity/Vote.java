package com.vomatt.entity;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import com.vomatt.entity.common.AuditableEntity;
import com.vomatt.entity.User;

import org.hibernate.annotations.BatchSize;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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
@Table(name = "votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"creator", "options", "memberVotes", "tags"})
public class Vote extends AuditableEntity {

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 1000)
    private String description;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private User creator;

    @Column(name = "start_time")
    private OffsetDateTime startTime;

    @Column(name = "end_time")
    private OffsetDateTime endTime;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "allow_multiple_choices")
    private boolean allowMultipleChoices = false;

    @Column(name = "is_anonymous")
    private boolean isAnonymous = false;

    @Column(name = "is_public")
    private boolean isPublic = true;

    @Column(name = "max_choices")
    private Integer maxChoices = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false)
    private VoteType voteType = VoteType.STANDARD;

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<VoteOption> options = new HashSet<>();

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserVote> userVotes = new HashSet<>();

    // Loaded for a whole page of votes in one IN query, instead of a join that multiplies option rows
    @BatchSize(size = 50)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "vote_tags",
        joinColumns = @JoinColumn(name = "vote_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    public Vote(String title, String description, User creator) {
        this.title = title;
        this.description = description;
        this.creator = creator;
        this.startTime = OffsetDateTime.now();
    }

    public Vote(String title, String description, User creator, OffsetDateTime endTime) {
        this(title, description, creator);
        this.endTime = endTime;
    }

    public boolean isVotingActive() {
        return hasStarted() && !hasEnded();
    }

    /** Past its start time (a vote without one is open from creation). */
    public boolean hasStarted() {
        return startTime == null || !OffsetDateTime.now().isBefore(startTime);
    }

    /** Cancelled, or past its end time. Results are only revealed then. */
    public boolean hasEnded() {
        return !isActive || (endTime != null && !OffsetDateTime.now().isBefore(endTime));
    }

    public boolean isCreatedBy(String userId) {
        return creator != null && creator.getId().toString().equals(userId);
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }

    public void addOption(VoteOption option) {
        options.add(option);
        option.setVote(this);
    }

    public void removeOption(VoteOption option) {
        options.remove(option);
        option.setVote(null);
    }

    public long getTotalVotes() {
        return userVotes.size();
    }

    public void deactivate() {
        this.isActive = false;
        this.endTime = OffsetDateTime.now();
    }
}

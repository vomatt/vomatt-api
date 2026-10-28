package com.vomatt.entity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.common.AuditableEntity;

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

    /** When the Poll Ends; equals the Deadline unless it ended early (Close). */
    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    /** The end time the owner set; ending early never moves it. */
    @Column(name = "deadline", nullable = false)
    private OffsetDateTime deadline;

    /** Set when the owner Deletes the Poll; a Deleted Poll is hidden from everyone but its data is kept. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** Turnout (Participants); written only by atomic SQL increments in {@code VoteRepository}, never by the entity. */
    @Column(name = "turnout", insertable = false, updatable = false)
    private int turnout;

    /** Distinct sharers; written only by atomic SQL increments in {@code VoteRepository}, never by the entity. */
    @Column(name = "share_count", insertable = false, updatable = false)
    private int shareCount;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "allow_multiple_choices")
    private boolean allowMultipleChoices = false;

    @Column(name = "is_anonymous")
    private boolean isAnonymous = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false)
    private Audience audience = Audience.PUBLIC;

    @Column(name = "max_choices")
    private Integer maxChoices = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "voter_visibility", nullable = false)
    private VoterVisibility voterVisibility = VoterVisibility.OWNER;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false)
    private VoteType voteType = VoteType.STANDARD;

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<VoteOption> options = new HashSet<>();

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserVote> userVotes = new HashSet<>();

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
        scheduleEnd(endTime);
    }

    public VoteStatus getStatus() {
        return statusAt(OffsetDateTime.now());
    }

    // Ended wins over Scheduled: a cancelled Scheduled Poll has end time before start time
    public VoteStatus statusAt(OffsetDateTime now) {
        if (endTime != null && !now.isBefore(endTime)) return VoteStatus.ENDED;
        if (startTime != null && now.isBefore(startTime)) return VoteStatus.SCHEDULED;
        return VoteStatus.OPEN;
    }

    /** Delete: allowed while Scheduled, or within {@code window} of posting even when it holds Ballots (ADR 0004). */
    public boolean canDeleteAt(OffsetDateTime now, Duration window) {
        return deletedAt == null
            && (statusAt(now) == VoteStatus.SCHEDULED || now.isBefore(getCreatedAt().plus(window)));
    }

    /** Audience can change only while Scheduled, so it is fixed once the Poll is Open. */
    public void changeAudience(Audience audience, OffsetDateTime now) {
        if (statusAt(now) != VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_NOT_EDITABLE);
        }
        this.audience = audience;
    }

    /**
     * Who can reach the Poll at all: nobody once Deleted; its owner always; nobody the owner blocked, Invitees
     * included; an Invite-only Poll only its owner and Invitees.
     *
     * @param viewerId         caller, or null when signed out
     * @param isInvitee        asked only for a signed-in non-owner of an Invite-only Poll
     * @param isBlockedByOwner asked only for a signed-in non-owner
     */
    public boolean isVisibleTo(String viewerId, BooleanSupplier isInvitee, BooleanSupplier isBlockedByOwner) {
        if (deletedAt != null) {
            return false;
        }
        if (isOwnedBy(viewerId)) {
            return true;
        }
        if (viewerId != null && isBlockedByOwner.getAsBoolean()) {
            return false;
        }
        return audience != Audience.INVITE_ONLY || (viewerId != null && isInvitee.getAsBoolean());
    }

    /** Invitees can be added or removed while the Poll is Scheduled or Open. */
    public void checkInviteesEditable(OffsetDateTime now) {
        if (audience != Audience.INVITE_ONLY) {
            throw ApiException.badRequest(MessageKey.VOTE_INVITEE_NOT_INVITE_ONLY);
        }
        if (statusAt(now) == VoteStatus.ENDED) {
            throw ApiException.badRequest(MessageKey.VOTE_ENDED);
        }
    }

    /** @param userId caller, or null when signed out */
    public boolean isOwnedBy(String userId) {
        return creator.getId().toString().equals(userId);
    }

    public boolean isVotingActive() {
        return getStatus() == VoteStatus.OPEN;
    }

    /** Sealed: nobody, including the owner, sees per-option counts or Support until the Poll has Ended. */
    public boolean isResultsSealed() {
        return getStatus() != VoteStatus.ENDED;
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }

    public void addOption(VoteOption option) {
        options.add(option);
        option.setVote(this);
    }

    /** Create / edit: the owner's end time is both the Deadline and when the Poll Ends. */
    public void scheduleEnd(OffsetDateTime endTime) {
        this.endTime = endTime;
        this.deadline = endTime;
    }

    /**
     * Close: an Open Unlisted or Invite-only Poll ends now, keeping its Deadline; an Ended Poll is left as is.
     * A Public Poll runs to its Deadline and a Scheduled Poll is Deleted instead (ADR 0004).
     */
    public void closeAt(OffsetDateTime now) {
        VoteStatus status = statusAt(now);
        if (status == VoteStatus.ENDED) {
            return;
        }
        if (status == VoteStatus.SCHEDULED) {
            throw ApiException.badRequest(MessageKey.VOTE_CLOSE_SCHEDULED);
        }
        if (audience == Audience.PUBLIC) {
            throw ApiException.badRequest(MessageKey.VOTE_CLOSE_PUBLIC_NOT_ALLOWED);
        }
        endAt(now);
    }

    /**
     * Auto-end: an Open Invite-only Poll Ends once every Invitee holds a Ballot, keeping its Deadline like a Close.
     * A Deleted Poll is left as is.
     *
     * @param allInviteesHoldBallot asked only for an Open, not Deleted Invite-only Poll; false when it has no Invitees
     * @return whether the Poll Ended now
     */
    public boolean endIfAllInviteesVotedAt(OffsetDateTime now, BooleanSupplier allInviteesHoldBallot) {
        if (deletedAt != null || audience != Audience.INVITE_ONLY || statusAt(now) != VoteStatus.OPEN
                || !allInviteesHoldBallot.getAsBoolean()) {
            return false;
        }
        endAt(now);
        return true;
    }

    private void endAt(OffsetDateTime now) {
        this.isActive = false;
        this.endTime = now;
    }
}

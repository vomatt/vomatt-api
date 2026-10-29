package com.vomatt.entity;

import com.vomatt.entity.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A user's bookmark on a Poll: listed in their bookmarked Polls, and the Poll's RESULT reaches them when it Ends. */
@Entity
@Table(name = "vote_bookmarks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote"})
public class VoteBookmark extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;
}

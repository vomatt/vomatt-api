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

/**
 * A block: {@code blocker} no longer sees Polls in public lists or Comments authored by {@code blocked}, and
 * {@code blocked} can no longer reach any Poll of {@code blocker} (Vote.isVisibleTo). Notifications drop both ways.
 */
@Entity
@Table(name = "user_blocks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"blocker", "blocked"})
public class UserBlock extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id")
    private User blocker;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id")
    private User blocked;
}

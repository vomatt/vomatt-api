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

/** One-way follow: {@code follower} sees Polls {@code followed} created in the Following list. */
@Entity
@Table(name = "user_follows")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"follower", "followed"})
public class UserFollow extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id")
    private User follower;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "followed_id")
    private User followed;
}

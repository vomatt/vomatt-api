package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
import com.vomatt.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "vote_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote"})
public class VoteComment extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id")
    private Vote vote;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @NotBlank
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    public VoteComment(Vote vote, User user, String content) {
        this.vote = vote;
        this.user = user;
        this.content = content;
    }

    public void updateContent(String newContent) {
        this.content = newContent;
    }

    public void softDelete() {
        this.isDeleted = true;
    }

    public boolean canBeEditedBy(String userId) {
        return this.user.getId().toString().equals(userId) && !this.isDeleted;
    }

    public boolean canBeDeletedBy(String userId) {
        return this.user.getId().toString().equals(userId) && !this.isDeleted;
    }
}

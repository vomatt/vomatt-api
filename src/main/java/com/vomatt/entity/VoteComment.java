package com.vomatt.entity;

import com.vomatt.entity.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Entity
@Table(name = "vote_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "vote", "parent"})
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

    /** Root Comment this Reply belongs to; null for a top-level Comment. Replies are one level deep. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private VoteComment parent;

    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    /** Users the text @mentions, resolved when it is written or edited; read by the MENTION notification. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mentioned_user_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] mentionedUserIds = new UUID[0];

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

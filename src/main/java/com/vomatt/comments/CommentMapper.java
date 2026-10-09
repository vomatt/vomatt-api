package com.vomatt.comments;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.entity.VoteComment;
import org.springframework.stereotype.Component;

@Component
public class CommentMapper {

    public CommentDto toDto(VoteComment comment, long likeCount, boolean isLikedByCurrentUser) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId().toString());
        dto.setVoteId(comment.getVote().getId().toString());
        dto.setParentId(comment.getParent() == null ? null : comment.getParent().getId().toString());
        dto.setDeleted(comment.isDeleted());
        // A deleted Comment kept as a placeholder for its Replies hides its text and author
        if (!comment.isDeleted()) {
            dto.setUserId(comment.getUser().getId().toString());
            dto.setAuthor(comment.getUser().getUsername());
            dto.setText(comment.getContent());
        }
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setUpdatedAt(comment.getUpdatedAt());
        // Insert stamps both times a few microseconds apart, so only a later save counts as an edit
        dto.setEdited(comment.getCreatedAt() != null && comment.getUpdatedAt() != null
                && comment.getUpdatedAt().isAfter(comment.getCreatedAt().plusSeconds(1)));
        dto.setLikeCount(likeCount);
        dto.setLikedByCurrentUser(isLikedByCurrentUser);
        return dto;
    }
}

package com.vomatt.comments;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.comments.dto.MentionDto;
import com.vomatt.entity.VoteComment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CommentMapper {

    /** {@code usernames} maps (at least) the comment's mentioned user ids to their current usernames. */
    public CommentDto toDto(VoteComment comment, long likeCount, boolean isLikedByCurrentUser,
                            Map<UUID, String> usernames) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId().toString());
        dto.setVoteId(comment.getVote().getId().toString());
        dto.setParentId(comment.getParent() == null ? null : comment.getParent().getId().toString());
        dto.setDeleted(comment.isDeleted());
        // A deleted Comment kept as a placeholder for its Replies hides its text and author
        if (!comment.isDeleted()) {
            dto.setUserId(comment.getUser().getId().toString());
            dto.setAuthor(comment.getUser().getUsername());
            dto.setAuthorAvatarUrl(comment.getUser().getAvatarUrl());
            dto.setText(comment.getContent());
            dto.setMentions(mentions(comment, usernames));
        }
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setUpdatedAt(comment.getUpdatedAt());
        // Soft delete also bumps updated_at, so a deleted placeholder is never "edited"
        dto.setEdited(!comment.isDeleted() && comment.getCreatedAt() != null && comment.getUpdatedAt() != null
                && comment.getUpdatedAt().isAfter(comment.getCreatedAt()));
        dto.setLikeCount(likeCount);
        dto.setLikedByCurrentUser(isLikedByCurrentUser);
        return dto;
    }

    // The stored mentions whose user still goes by a handle in the text (as an edit keeps them), in text order; a
    // renamed user's old handle no longer points at them, so it stays plain text
    private static List<MentionDto> mentions(VoteComment comment, Map<UUID, String> usernames) {
        Map<String, UUID> byUsername = Arrays.stream(comment.getMentionedUserIds())
            .filter(usernames::containsKey)
            .collect(Collectors.toMap(usernames::get, Function.identity()));
        return VoteCommentService.mentionedUsernames(comment.getContent()).stream()
            .map(username -> {
                UUID id = byUsername.get(username);
                return id == null ? null : new MentionDto(id.toString(), username);
            })
            .filter(Objects::nonNull)
            .toList();
    }
}

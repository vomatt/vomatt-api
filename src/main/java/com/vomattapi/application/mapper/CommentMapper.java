package com.vomattapi.application.mapper;

import com.vomattapi.application.dto.response.CommentDto;
import com.vomattapi.domain.vote.VoteComment;
import org.springframework.stereotype.Component;

@Component
public class CommentMapper {

    public CommentDto toDto(VoteComment comment, long likeCount, boolean isLikedByCurrentUser) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId().toString());
        dto.setVoteId(comment.getVote().getId().toString());
        dto.setUserId(comment.getUser().getId().toString());
        dto.setAuthor(comment.getUser().getUsername());
        dto.setText(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setUpdatedAt(comment.getUpdatedAt());
        dto.setEdited(!comment.getCreatedAt().equals(comment.getUpdatedAt()));
        dto.setLikeCount(likeCount);
        dto.setLikedByCurrentUser(isLikedByCurrentUser);
        return dto;
    }
}

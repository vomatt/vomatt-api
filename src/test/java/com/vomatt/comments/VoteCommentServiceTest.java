package com.vomatt.comments;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommentLikeCount;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteCommentService.getCommentsByVote")
class VoteCommentServiceTest {

    @Mock VoteCommentRepository commentRepository;
    @Mock VoteRepository voteRepository;
    @Mock UserRepository userRepository;
    @Mock CommentLikeRepository commentLikeRepository;
    @Spy CommentMapper commentMapper = new CommentMapper();
    @InjectMocks VoteCommentService service;

    private final UUID voteId = UUID.randomUUID();
    private VoteComment liked;
    private VoteComment plain;

    private VoteComment comment(String text) {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        User user = User.builder().id(UUID.randomUUID()).username("mei").build();
        VoteComment c = new VoteComment(vote, user, text);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(c, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(c, "updatedAt", OffsetDateTime.now());
        return c;
    }

    private static CommentLikeCount likes(UUID commentId, long count) {
        return new CommentLikeCount() {
            public UUID getCommentId() { return commentId; }
            public Long getCount() { return count; }
        };
    }

    @BeforeEach
    void setUp() {
        liked = comment("Saturday works");
        plain = comment("Friday please");
        when(voteRepository.existsById(voteId)).thenReturn(true);
        Page<VoteComment> page = new PageImpl<>(List.of(liked, plain));
        when(commentRepository.findByVoteId(eq(voteId), any())).thenReturn(page);
        when(commentLikeRepository.countByCommentIds(any())).thenReturn(List.of(likes(liked.getId(), 3)));
    }

    @Test
    @DisplayName("應該以一次查詢帶出每則留言的按讚數，以及登入者按過讚的留言")
    void shouldMapLikesForThePage() {
        UUID viewer = UUID.randomUUID();
        when(commentLikeRepository.findLikedCommentIds(eq(viewer), any())).thenReturn(List.of(liked.getId()));

        List<CommentDto> dtos = service.getCommentsByVote(voteId.toString(), PageRequest.of(0, 20),
                viewer.toString()).getContent();

        assertThat(dtos).extracting(CommentDto::getLikeCount).containsExactly(3L, 0L);
        assertThat(dtos).extracting(CommentDto::isLikedByCurrentUser).containsExactly(true, false);
    }

    @Test
    @DisplayName("訪客不應查詢按讚狀態")
    void shouldSkipLikedLookupForGuests() {
        List<CommentDto> dtos = service.getCommentsByVote(voteId.toString(), PageRequest.of(0, 20), null)
                .getContent();

        assertThat(dtos).extracting(CommentDto::isLikedByCurrentUser).containsExactly(false, false);
        verify(commentLikeRepository, never()).findLikedCommentIds(any(), any());
    }
}

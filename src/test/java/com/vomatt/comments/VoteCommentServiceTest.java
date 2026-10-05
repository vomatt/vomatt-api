package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.vomatt.comments.dto.CommentDto;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.repository.VoteRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteCommentService")
class VoteCommentServiceTest {

    @Mock VoteCommentRepository commentRepository;
    @Mock VoteRepository voteRepository;
    @Mock UserRepository userRepository;
    @Mock CommentLikeRepository commentLikeRepository;
    @Spy CommentMapper commentMapper = new CommentMapper();
    @InjectMocks VoteCommentService commentService;

    private final UUID voteId = UUID.randomUUID();
    private final UUID viewerId = UUID.randomUUID();

    private VoteComment comment(Vote vote, User author, String text) {
        VoteComment c = new VoteComment(vote, author, text);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        OffsetDateTime at = OffsetDateTime.now();
        ReflectionTestUtils.setField(c, "createdAt", at);
        ReflectionTestUtils.setField(c, "updatedAt", at);
        return c;
    }

    private static IdCount count(UUID id, long n) {
        IdCount c = mock(IdCount.class);
        when(c.getId()).thenReturn(id);
        when(c.getCount()).thenReturn(n);
        return c;
    }

    @Test
    @DisplayName("應該每頁一次載入讚數與是否已按讚")
    void shouldLoadLikesPerPage() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        User author = User.builder().id(UUID.randomUUID()).username("author").build();
        VoteComment liked = comment(vote, author, "liked");
        VoteComment plain = comment(vote, author, "plain");
        when(voteRepository.existsById(voteId)).thenReturn(true);
        when(commentRepository.findPageByVoteId(eq(voteId), any(), any(), eq(Limit.of(21))))
                .thenReturn(List.of(liked, plain));
        IdCount likedCount = count(liked.getId(), 3);
        when(commentLikeRepository.countByCommentIds(List.of(liked.getId(), plain.getId())))
                .thenReturn(List.of(likedCount));
        when(commentLikeRepository.findLikedCommentIds(viewerId, List.of(liked.getId(), plain.getId())))
                .thenReturn(List.of(liked.getId()));

        CursorResponse<CommentDto> page = commentService.getCommentsByVote(voteId.toString(), null, null,
                viewerId.toString());

        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).extracting(CommentDto::getText, CommentDto::getLikeCount, CommentDto::isLikedByCurrentUser)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("liked", 3L, true),
                        org.assertj.core.groups.Tuple.tuple("plain", 0L, false));
    }

    @Test
    @DisplayName("應該在 Poll 不存在時回 404")
    void shouldThrowWhenPollMissing() {
        when(voteRepository.existsById(voteId)).thenReturn(false);

        assertThatThrownBy(() -> commentService.getCommentsByVote(voteId.toString(), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND);
                });
    }
}

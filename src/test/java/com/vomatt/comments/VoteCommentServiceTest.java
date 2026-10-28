package com.vomatt.comments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
import com.vomatt.common.dto.ReportRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.ReportReason;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteComment;
import com.vomatt.repository.CommentLikeRepository;
import com.vomatt.repository.CommentReportRepository;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.VoteCommentRepository;
import com.vomatt.votes.VoteService;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteCommentService")
class VoteCommentServiceTest {

    @Mock VoteCommentRepository commentRepository;
    @Mock VoteService voteService;
    @Mock UserRepository userRepository;
    @Mock CommentLikeRepository commentLikeRepository;
    @Mock CommentReportRepository commentReportRepository;
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
    @DisplayName("should load like counts and liked state once per page")
    void shouldLoadLikesPerPage() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        User author = User.builder().id(UUID.randomUUID()).username("author").build();
        VoteComment liked = comment(vote, author, "liked");
        VoteComment plain = comment(vote, author, "plain");
        when(voteService.findAccessibleVote(voteId, viewerId.toString())).thenReturn(vote);
        when(commentRepository.findPageByVoteId(eq(voteId), any(), any(), eq(viewerId), eq(Limit.of(21))))
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
    @DisplayName("should return 404 when the Poll lookup refuses it (missing or Deleted)")
    void shouldThrowWhenPollMissing() {
        when(voteService.findAccessibleVote(voteId, null)).thenThrow(ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

        assertThatThrownBy(() -> commentService.getCommentsByVote(voteId.toString(), null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("should refuse edit and delete with 403 COMMENT_FORBIDDEN when the caller is not the author")
    void shouldForbidEditAndDeleteWhenCallerIsNotAuthor() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        VoteComment target = comment(vote, User.builder().id(UUID.randomUUID()).username("author").build(), "mine");
        when(commentRepository.findByIdAndNotDeleted(target.getId())).thenReturn(java.util.Optional.of(target));
        when(voteService.findAccessibleVote(voteId, viewerId.toString())).thenReturn(vote);
        com.vomatt.comments.dto.UpdateCommentRequest edit = new com.vomatt.comments.dto.UpdateCommentRequest();
        edit.setText("hijacked");

        assertThatThrownBy(() -> commentService.updateComment(target.getId(), viewerId.toString(), edit))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_FORBIDDEN);
                });
        assertThatThrownBy(() -> commentService.deleteComment(target.getId(), viewerId.toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_FORBIDDEN));

        assertThat(target.getContent()).isEqualTo("mine");
        assertThat(target.isDeleted()).isFalse();
        org.mockito.Mockito.verify(commentRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(commentRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should return vote.not_found when liking a Comment of a Deleted Poll")
    void shouldThrowWhenLikingCommentOfDeletedPoll() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        VoteComment target = comment(vote, User.builder().id(UUID.randomUUID()).username("author").build(), "hi");
        when(commentRepository.findByIdAndNotDeleted(target.getId())).thenReturn(java.util.Optional.of(target));
        when(voteService.findAccessibleVote(voteId, viewerId.toString()))
                .thenThrow(ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

        assertThatThrownBy(() -> commentService.likeComment(target.getId(), viewerId.toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.VOTE_NOT_FOUND));
    }

    @Test
    @DisplayName("should return comment.not_found when the Comment goes between its lookup and the like")
    void shouldThrowCommentNotFoundWhenCommentDeletedBeforeLikeInsert() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        VoteComment target = comment(vote, User.builder().id(UUID.randomUUID()).username("author").build(), "hi");
        when(commentRepository.findByIdAndNotDeleted(target.getId())).thenReturn(Optional.of(target), Optional.empty());
        when(commentLikeRepository.insertIfAbsent(any(), eq(target.getId()), eq(viewerId))).thenReturn(0);
        when(userRepository.existsById(viewerId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.likeComment(target.getId(), viewerId.toString()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("should return comment.not_found when the Comment goes between its lookup and the report")
    void shouldThrowCommentNotFoundWhenCommentDeletedBeforeReportInsert() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        VoteComment target = comment(vote, User.builder().id(UUID.randomUUID()).username("author").build(), "hi");
        when(commentRepository.findByIdAndNotDeleted(target.getId())).thenReturn(Optional.of(target), Optional.empty());
        when(commentReportRepository.insertIfAbsent(any(), eq(target.getId()), eq(viewerId), any(), any())).thenReturn(0);
        when(userRepository.existsById(viewerId)).thenReturn(true);

        assertThatThrownBy(() -> commentService.reportComment(target.getId(), viewerId.toString(),
                new ReportRequest(ReportReason.SPAM, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo(MessageKey.COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("should succeed without telling 404 causes apart when the caller already liked the Comment")
    void shouldSkipNotFoundChecksWhenAlreadyLiked() {
        Vote vote = new Vote();
        ReflectionTestUtils.setField(vote, "id", voteId);
        VoteComment target = comment(vote, User.builder().id(UUID.randomUUID()).username("author").build(), "hi");
        when(commentRepository.findByIdAndNotDeleted(target.getId())).thenReturn(Optional.of(target));
        when(commentLikeRepository.insertIfAbsent(any(), eq(target.getId()), eq(viewerId))).thenReturn(0);
        when(commentLikeRepository.existsByCommentIdAndUserId(target.getId(), viewerId)).thenReturn(true);

        commentService.likeComment(target.getId(), viewerId.toString());

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).existsById(any());
        org.mockito.Mockito.verify(commentRepository, org.mockito.Mockito.times(1)).findByIdAndNotDeleted(target.getId());
    }
}

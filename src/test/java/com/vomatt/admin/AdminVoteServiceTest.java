package com.vomatt.admin;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminVoteService")
class AdminVoteServiceTest {

    @Mock AdminQueryRepository adminQueryRepository;
    @Mock VoteRepository voteRepository;
    @Mock TagRepository tagRepository;
    @Mock VoteService voteService;
    @InjectMocks AdminVoteService service;

    final UUID voteId = UUID.randomUUID();
    final String adminId = UUID.randomUUID().toString();
    Vote vote;
    Tag tag;

    @BeforeEach
    void setUp() {
        User owner = User.builder().username("owner").email("owner@test.local").build();
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        vote = new Vote("Poll", null, owner, now.plusDays(1));
        vote.setStartTime(now.minusDays(2));
        ReflectionTestUtils.setField(vote, "id", voteId);
        // Posted long ago: the owner's delete window has passed
        ReflectionTestUtils.setField(vote, "createdAt", now.minusDays(2));
        tag = new Tag();
        ReflectionTestUtils.setField(tag, "id", UUID.randomUUID());
        vote.addTag(tag);
        // Every write locks the Poll first; list tests never reach it
        lenient().when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
    }

    private void stubResponse() {
        when(voteService.toResponses(anyList(), isNull())).thenReturn(List.of(new VoteResponse()));
    }

    @Test
    void shouldCloseOpenUnlistedPollWhenCallerIsNotOwner() {
        vote.setAudience(Audience.UNLISTED);
        stubResponse();

        service.closeVote(voteId, adminId);

        assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
        assertThat(vote.isActive()).isFalse();
        verify(voteRepository).setLockTimeout();
    }

    @Test
    void shouldKeepPublicCloseRuleWhenAdminCloses() {
        assertThatThrownBy(() -> service.closeVote(voteId, adminId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.VOTE_CLOSE_PUBLIC_NOT_ALLOWED));
        assertThat(vote.getStatus()).isEqualTo(VoteStatus.OPEN);
    }

    @Test
    void shouldSoftDeleteAndReleaseTagsWhenOwnerWindowPassed() {
        service.deleteVote(voteId, adminId);

        assertThat(vote.getDeletedAt()).isNotNull();
        verify(tagRepository).decrementUsageCount(Set.of(tag.getId()));
    }

    @Test
    void shouldLeaveTagsAloneWhenDeletingAlreadyDeletedPoll() {
        OffsetDateTime deletedAt = OffsetDateTime.now().minusHours(1);
        vote.setDeletedAt(deletedAt);

        service.deleteVote(voteId, adminId);

        assertThat(vote.getDeletedAt()).isEqualTo(deletedAt);
        verify(tagRepository, never()).decrementUsageCount(any());
    }

    @Test
    void shouldNotTouchTagsWhenDeletingNonPublicPoll() {
        vote.setAudience(Audience.INVITE_ONLY);

        service.deleteVote(voteId, adminId);

        assertThat(vote.getDeletedAt()).isNotNull();
        verify(tagRepository, never()).decrementUsageCount(any());
    }

    @Test
    void shouldClearDeletedAtAndRecountTagsWhenRestoring() {
        vote.setDeletedAt(OffsetDateTime.now().minusHours(1));
        stubResponse();

        var response = service.restoreVote(voteId, adminId);

        assertThat(vote.getDeletedAt()).isNull();
        assertThat(response.deletedAt()).isNull();
        verify(tagRepository).incrementUsageCount(Set.of(tag.getId()));
    }

    @Test
    void shouldLeaveTagsAloneWhenRestoringLivePoll() {
        stubResponse();

        service.restoreVote(voteId, adminId);

        verify(tagRepository, never()).incrementUsageCount(any());
    }

    @Test
    void shouldReturn404WhenPollMissing() {
        UUID missing = UUID.randomUUID();
        when(voteRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteVote(missing, adminId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void shouldReturn409WhenPollLockTimesOut() {
        when(voteRepository.findByIdForUpdate(voteId)).thenThrow(new PessimisticLockingFailureException("busy"));

        assertThatThrownBy(() -> service.deleteVote(voteId, adminId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.COMMON_CONFLICT));
    }

    @Test
    void shouldRejectSingleCharacterQueryWhenListing() {
        assertThatThrownBy(() -> service.listVotes("a", null, null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo(MessageKey.VOTE_SEARCH_QUERY_TOO_SHORT));
    }
}

package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.VoteRequest;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteService")
class VoteServiceTest {

    @Mock VoteRepository voteRepository;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock UserVoteRepository userVoteRepository;
    @Mock UserRepository userRepository;
    @Mock TagRepository tagRepository;
    @Mock VoteConfigurationProperties voteConfig;
    @Mock com.vomatt.repository.VoteCommentRepository voteCommentRepository;
    @Spy VoteMapper voteMapper = new VoteMapper();

    @InjectMocks
    VoteService voteService;

    private UUID userId;
    private UUID voteId;
    private User user;
    private Vote vote;
    private VoteOption option;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        voteId = UUID.randomUUID();

        user = User.builder().id(userId).username("testuser").build();

        vote = new Vote();
        vote.setTitle("Test Vote");
        vote.setCreator(user);
        setId(vote, voteId);

        option = new VoteOption();
        option.setVote(vote);
    }

    // ─── helper ───────────────────────────────────────────────────────────────

    private static <T> T setId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static void assertApiException(Throwable ex, HttpStatus status, MessageKey key) {
        assertThat(ex).isInstanceOf(ApiException.class);
        ApiException apiEx = (ApiException) ex;
        assertThat(apiEx.getStatus()).isEqualTo(status);
        assertThat(apiEx.getMessageKey()).isEqualTo(key);
    }

    private void stubConvertToVoteResponse(Vote v) {
        when(voteRepository.findByIdWithOptions(v.getId())).thenReturn(Optional.of(v));
    }

    private void stubSaveAssignsId() {
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
            Vote v = inv.getArgument(0);
            if (v.getId() == null) {
                setId(v, UUID.randomUUID()); // 模擬 DB 分配 ID
            }
            v.getOptions().stream().filter(o -> o.getId() == null).forEach(o -> setId(o, UUID.randomUUID()));
            return v;
        });
    }

    private CreateVoteRequest.VoteOptionRequest buildOption(String text) {
        CreateVoteRequest.VoteOptionRequest opt = new CreateVoteRequest.VoteOptionRequest();
        opt.setText(text);
        return opt;
    }

    // ─── createVote ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createVote")
    class CreateVoteTests {

        @Test
        @DisplayName("應該在選項數量不足時拋出 400 VOTE_OPTIONS_MIN")
        void shouldThrowWhenTooFewOptions() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> {
                        assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_OPTIONS_MIN);
                        assertThat(((ApiException) ex).getMessageArgs()).containsExactly(2);
                    });
        }

        @Test
        @DisplayName("應該在選項數量超過上限時拋出 400 VOTE_OPTIONS_MAX")
        void shouldThrowWhenTooManyOptions() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(2);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B"), buildOption("C")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> {
                        assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_OPTIONS_MAX);
                        assertThat(((ApiException) ex).getMessageArgs()).containsExactly(2);
                    });
        }

        @Test
        @DisplayName("應該在結束時間已過去時拋出 400 VOTE_END_TIME_PAST")
        void shouldThrowWhenEndTimeInPast() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setEndTime(OffsetDateTime.now().minusDays(1));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_END_TIME_PAST));
        }

        @Test
        @DisplayName("應該在要求複選時拋出 400 VOTE_MULTIPLE_NOT_ALLOWED")
        void shouldRejectCreateWhenMultipleChoicesRequested() {
            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setEndTime(OffsetDateTime.now().plusDays(1));
            request.setAllowMultipleChoices(true);

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_MULTIPLE_NOT_ALLOWED));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("應該在找不到建立者時拋出 404 USER_NOT_FOUND")
        void shouldThrowWhenCreatorNotFound() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.USER_NOT_FOUND));
        }

        @Test
        @DisplayName("應該成功建立投票並附上選項")
        void shouldCreateVote() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubSaveAssignsId();

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Favourite Color?");
            request.setOptions(List.of(buildOption("Red"), buildOption("Blue")));


            VoteResponse result = voteService.createVote(request, userId.toString());

            assertThat(result.getTitle()).isEqualTo("Favourite Color?");
            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getText)
                    .containsExactlyInAnyOrder("Red", "Blue");
            // a new Poll is Open: counts are Sealed
            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
            verify(voteRepository, times(2)).save(argThat(v ->
                    "Favourite Color?".equals(v.getTitle()) && v.getCreator() == user));
        }
    }

    // ─── getVote ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getVote")
    class GetVoteTests {

        @Test
        @DisplayName("應該在找不到投票時拋出 404 VOTE_NOT_FOUND")
        void shouldThrowWhenVoteNotFound() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> voteService.getVote(voteId.toString(), null))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND));
        }

        @Test
        @DisplayName("應該成功回傳投票")
        void shouldReturnVoteWhenFound() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            stubConvertToVoteResponse(vote);

            VoteResponse result = voteService.getVote(voteId.toString(), null);

            assertThat(result.getId()).isEqualTo(voteId.toString());
            assertThat(result.getTitle()).isEqualTo("Test Vote");
        }
    }

    // ─── vote ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("vote")
    class CastVoteTests {

        @Test
        @DisplayName("應該在投票不存在或已停用時拋出 404 VOTE_NOT_FOUND")
        void shouldThrowWhenVoteNotActive() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.empty());

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND));
        }

        @Test
        @DisplayName("應該在單選投票傳入多個選項時拋出 400 VOTE_MULTIPLE_NOT_ALLOWED")
        void shouldThrowWhenMultipleChoicesNotAllowed() {
            vote.setActive(true);
            vote.setAllowMultipleChoices(false);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_MULTIPLE_NOT_ALLOWED));

            verify(userVoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("應該在選項不屬於該投票時拋出 400 VOTE_OPTION_NOT_IN_VOTE")
        void shouldThrowWhenOptionBelongsToDifferentVote() {
            vote.setActive(true);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            UUID optionId = UUID.randomUUID();
            Vote differentVote = setId(new Vote(), UUID.randomUUID()); // different from voteId
            option.setVote(differentVote);
            when(voteOptionRepository.findById(optionId)).thenReturn(Optional.of(option));

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(optionId.toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_OPTION_NOT_IN_VOTE));

            verify(userVoteRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Ballot rules")
    class BallotRuleTests {

        private VoteRequest singleOption() {
            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString()));
            return request;
        }

        @Test
        @DisplayName("應該在 Poll 已結束時以 vote.ended 拒絕投票")
        void shouldRejectVoteWhenPollEndedWithVoteEndedCode() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), singleOption(), userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("應該在 Poll 尚未開始時拒絕投票")
        void shouldRejectVoteWhenPollScheduled() {
            vote.setStartTime(OffsetDateTime.now().plusHours(1));
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), singleOption(), userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_NOT_ALLOWED));
        }

        @Test
        @DisplayName("應該在 Poll 已結束時以 vote.ended 拒絕移除選擇")
        void shouldRejectRemovalWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.removeVote(voteId.toString(), UUID.randomUUID().toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("應該在 Poll 已結束時以 vote.ended 拒絕撤回")
        void shouldRejectRetractionWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.retract(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("應該在檢視者有 Ballot 時回傳 myOptionId")
        void shouldReturnMyOptionIdWhenViewerHasBallot() {
            UUID optionId = UUID.randomUUID();
            setId(option, optionId);
            option.setText("A");
            option.setDisplayOrder(0);
            vote.addOption(option);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            stubConvertToVoteResponse(vote);
            com.vomatt.repository.BallotSelection selection = mock(com.vomatt.repository.BallotSelection.class);
            when(selection.getVoteId()).thenReturn(voteId);
            when(selection.getOptionId()).thenReturn(optionId);
            when(userVoteRepository.findSelections(userId, List.of(voteId))).thenReturn(List.of(selection));

            VoteResponse result = voteService.getVote(voteId.toString(), userId.toString());

            assertThat(result.getMyOptionId()).isEqualTo(optionId.toString());
        }

        @Test
        @DisplayName("應該在 Poll Open 時對 /results 回 403 vote.results.sealed")
        void shouldReturnForbiddenForResultsWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.getVoteResults(voteId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_RESULTS_SEALED));
        }

        @Test
        @DisplayName("應該在 Poll Ended 後回傳結果")
        void shouldReturnResultsWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId)).thenReturn(List.of());

            assertThat(voteService.getVoteResults(voteId.toString()).getTotalParticipants()).isZero();
        }

        @Test
        @DisplayName("應該在 GET 詳情時於 Poll Open 期間封存選項票數")
        void shouldOmitOptionCountsOnGetWhenPollOpen() {
            setId(option, UUID.randomUUID());
            option.setDisplayOrder(0);
            ReflectionTestUtils.setField(option, "voteCount", 5);
            vote.addOption(option);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            stubConvertToVoteResponse(vote);

            VoteResponse result = voteService.getVote(voteId.toString(), null);

            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
            assertThat(result.getParticipantCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("應該在未登入時 myOptionId 為 null")
        void shouldReturnNullMyOptionIdWhenSignedOut() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            stubConvertToVoteResponse(vote);

            assertThat(voteService.getVote(voteId.toString(), null).getMyOptionId()).isNull();
            verify(userVoteRepository, never()).findSelections(any(), any());
        }
    }

    @Nested
    @DisplayName("getVoters")
    class VoterVisibilityTests {

        private void endedWith(com.vomatt.entity.VoterVisibility visibility) {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            vote.setVoterVisibility(visibility);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        }

        private void assertHidden(String viewerId) {
            assertThatThrownBy(() -> voteService.getVoters(voteId.toString(), viewerId, null, null))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_VOTERS_HIDDEN));
        }

        @Test
        @DisplayName("應該在可見度為 NOBODY 時連發起人也看不到投票者")
        void shouldHideVotersWhenVisibilityIsNobody() {
            endedWith(com.vomatt.entity.VoterVisibility.NOBODY);
            assertHidden(userId.toString());
        }

        @Test
        @DisplayName("應該在可見度為 OWNER 時只開放給發起人")
        void shouldShowVotersOnlyToOwnerWhenVisibilityIsOwner() {
            endedWith(com.vomatt.entity.VoterVisibility.OWNER);
            when(userVoteRepository.findVoterPage(eq(voteId), isNull(), isNull(), any())).thenReturn(List.of());

            assertThat(voteService.getVoters(voteId.toString(), userId.toString(), null, null).items()).isEmpty();
            assertHidden(UUID.randomUUID().toString());
        }

        @Test
        @DisplayName("應該在可見度為 SIGNED_IN 時開放給任何登入使用者")
        void shouldShowVotersToAnySignedInUserWhenVisibilityIsSignedIn() {
            endedWith(com.vomatt.entity.VoterVisibility.SIGNED_IN);
            when(userVoteRepository.findVoterPage(eq(voteId), isNull(), isNull(), any())).thenReturn(List.of());

            assertThat(voteService.getVoters(voteId.toString(), UUID.randomUUID().toString(), null, null).nextCursor())
                    .isNull();
        }

        @Test
        @DisplayName("應該在 Poll 尚未結束時拒絕查看投票者")
        void shouldRejectVotersWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            vote.setVoterVisibility(com.vomatt.entity.VoterVisibility.SIGNED_IN);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.getVoters(voteId.toString(), userId.toString(), null, null))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_RESULTS_SEALED));
        }
    }

    @Nested
    @DisplayName("updateVote")
    class UpdateVoteTests {

        private CreateVoteRequest editRequest(List<UUID> tagIds) {
            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Edited");
            request.setOptions(List.of(buildOption("X"), buildOption("Y"), buildOption("Z")));
            request.setStartTime(OffsetDateTime.now().plusHours(2));
            request.setEndTime(OffsetDateTime.now().plusDays(2));
            request.setTagIds(tagIds);
            return request;
        }

        private void scheduled() {
            vote.setStartTime(OffsetDateTime.now().plusHours(1));
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        }

        @Test
        @DisplayName("應該在 Poll 已開始時拒絕編輯")
        void shouldRejectEditWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), editRequest(null), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_NOT_EDITABLE));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("應該在非發起人編輯時回 403")
        void shouldRejectEditWhenNotOwner() {
            scheduled();

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), editRequest(null), UUID.randomUUID().toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN));
        }

        @Test
        @DisplayName("應該在編輯 Scheduled Poll 時取代題目與選項")
        void shouldReplaceFieldsAndOptionsWhenScheduledPollEdited() {
            scheduled();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(java.time.Duration.ofDays(365));
            stubSaveAssignsId();

            VoteResponse result = voteService.updateVote(voteId.toString(), editRequest(null), userId.toString());

            assertThat(result.getTitle()).isEqualTo("Edited");
            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getText)
                    .containsExactlyInAnyOrder("X", "Y", "Z");
        }

        @Test
        @DisplayName("應該在編輯標籤時調整 usage_count：移除的減一、新增的加一")
        void shouldAdjustTagUsageWhenTagsEdited() {
            Tag kept = setId(new Tag("Kept", "kept", null, 1), UUID.randomUUID());
            Tag dropped = setId(new Tag("Dropped", "dropped", null, 2), UUID.randomUUID());
            Tag added = setId(new Tag("Added", "added", null, 3), UUID.randomUUID());
            vote.addTag(kept);
            vote.addTag(dropped);
            scheduled();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(java.time.Duration.ofDays(365));
            when(tagRepository.findAllByIdIn(Set.of(kept.getId(), added.getId()))).thenReturn(List.of(kept, added));
            stubSaveAssignsId();

            voteService.updateVote(voteId.toString(), editRequest(List.of(kept.getId(), added.getId())), userId.toString());

            verify(tagRepository).decrementUsageCount(Set.of(dropped.getId()));
            verify(tagRepository).incrementUsageCount(Set.of(added.getId()));
        }
    }

    // ─── deactivateVote ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateVote")
    class DeactivateVoteTests {

        @Test
        @DisplayName("應該在非建立者嘗試停用時拋出 403 VOTE_FORBIDDEN")
        void shouldThrowWhenNotCreator() {
            User creator = User.builder().id(UUID.randomUUID()).build(); // different from userId
            vote.setCreator(creator);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.deactivateVote(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN));

            assertThat(vote.isActive()).isTrue();
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("建立者應該能成功停用投票")
        void shouldDeactivateWhenCreator() {
            vote.setCreator(user);
            vote.setActive(true);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.save(vote)).thenReturn(vote);
            stubConvertToVoteResponse(vote);

            voteService.deactivateVote(voteId.toString(), userId.toString());

            assertThat(vote.isActive()).isFalse();
            assertThat(vote.getEndTime()).isNotNull();
            verify(voteRepository).save(vote);
        }
    }

    // ─── hasUserVoted ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasUserVoted 應該委派至 repository")
    void shouldDelegateHasUserVotedToRepository() {
        when(userVoteRepository.existsByUserIdAndVoteId(userId, voteId)).thenReturn(true);
        assertThat(voteService.hasUserVoted(voteId.toString(), userId.toString())).isTrue();
    }

    // ─── createVote with tags ─────────────────────────────────────────────────

    @Nested
    @DisplayName("createVote with tags")
    class CreateVoteWithTags {

        @Test
        @DisplayName("應該在建立投票時關聯標籤並更新 usageCount")
        void shouldCreateVoteWithTags() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubSaveAssignsId();

            UUID tagId = UUID.randomUUID();
            Tag tag = setId(new Tag("Tech", "tech", "Technology", 1), tagId);
            when(tagRepository.findAllByIdIn(Set.of(tagId))).thenReturn(List.of(tag));

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Tag Vote");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setTagIds(List.of(tagId));

            voteService.createVote(request, userId.toString());

            verify(tagRepository).findAllByIdIn(Set.of(tagId));
            verify(tagRepository).incrementUsageCount(Set.of(tagId));
        }

        @Test
        @DisplayName("應該在 tagIds 包含不存在的 ID 時拋出 400 TAG_IDS_INVALID")
        void shouldThrowWhenTagIdNotFound() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            // Only return one tag when two are requested
            Tag tag = setId(new Tag("Tech", "tech", "Technology", 1), tagId1);
            when(tagRepository.findAllByIdIn(Set.of(tagId1, tagId2))).thenReturn(List.of(tag));

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Tag Vote");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setTagIds(List.of(tagId1, tagId2));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.TAG_IDS_INVALID));

            verify(voteRepository, never()).save(any());
            verify(tagRepository, never()).incrementUsageCount(any());
        }

        @Test
        @DisplayName("應該在不帶 tagIds 時正常建立投票")
        void shouldCreateVoteWithoutTags() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubSaveAssignsId();

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("No Tag Vote");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            // tagIds not set (null)

            voteService.createVote(request, userId.toString());

            verify(tagRepository, never()).findAllByIdIn(any());
            verify(tagRepository, never()).incrementUsageCount(any());
        }
    }
}

package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Invitee;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.IdCount;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.TagRepository;
import com.vomatt.repository.UserRepository;
import com.vomatt.repository.UserVoteRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteesRequest;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    @Mock InviteeRepository inviteeRepository;
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
        ReflectionTestUtils.setField(vote, "createdAt", OffsetDateTime.now());
        lenient().when(voteConfig.getDeleteWindow()).thenReturn(Duration.ofMinutes(20));

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
        List<Vote> saved = new ArrayList<>();
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
            Vote v = inv.getArgument(0);
            if (v.getId() == null) {
                setId(v, UUID.randomUUID()); // simulate DB-assigned ID
                ReflectionTestUtils.setField(v, "createdAt", OffsetDateTime.now()); // simulate @PrePersist
            }
            v.getOptions().stream().filter(o -> o.getId() == null).forEach(o -> setId(o, UUID.randomUUID()));
            saved.add(v);
            return v;
        });
        // The response is reloaded with its options after saving
        when(voteRepository.findByIdWithOptions(any(UUID.class))).thenAnswer(inv -> Optional.of(saved.getLast()));
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
        @DisplayName("should throw 400 VOTE_OPTIONS_MIN when too few options")
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
        @DisplayName("should throw 400 VOTE_OPTIONS_MAX when too many options")
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
        @DisplayName("should throw 400 VOTE_END_TIME_PAST when end time is in the past")
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
        @DisplayName("should throw 400 VOTE_MULTIPLE_NOT_ALLOWED when multiple choice is requested")
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
        @DisplayName("should throw 400 VOTE_END_BEFORE_START when end time equals start time")
        void shouldRejectCreateWhenEndEqualsStart() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            OffsetDateTime start = OffsetDateTime.now().plusDays(1);
            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setStartTime(start);
            request.setEndTime(start);

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_END_BEFORE_START));
        }

        @Test
        @DisplayName("should throw 400 VOTE_INVITEE_NOT_INVITE_ONLY when inviteeIds are sent for a Public Poll")
        void shouldRejectCreateWhenInviteesSentForPublicPoll() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setInviteeIds(List.of(UUID.randomUUID()));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_INVITEE_NOT_INVITE_ONLY));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should invite each user once, skipping the owner, when an Invite-only Poll is created")
        void shouldInviteDistinctUsersWhenInviteOnlyPollCreated() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(Duration.ofDays(365));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubSaveAssignsId();
            User bob = User.builder().id(UUID.randomUUID()).username("bob").build();
            when(userRepository.findAllById(Set.of(bob.getId()))).thenReturn(List.of(bob));

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setEndTime(OffsetDateTime.now().plusDays(1));
            request.setAudience(Audience.INVITE_ONLY);
            request.setInviteeIds(List.of(bob.getId(), bob.getId(), userId));

            VoteResponse response = voteService.createVote(request, userId.toString());

            verify(inviteeRepository).saveAllAndFlush(argThat(invitees ->
                    invitees instanceof List<?> list && list.size() == 1
                            && ((Invitee) list.getFirst()).getUser() == bob));
            assertThat(response.getAudience()).isEqualTo(Audience.INVITE_ONLY);
            assertThat(response.getInviteeCount()).isZero();
        }

        @Test
        @DisplayName("should throw 404 USER_NOT_FOUND when creator is not found")
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
        @DisplayName("should create Poll with options")
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
            assertThat(result.getAudience()).isEqualTo(Audience.PUBLIC);
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
        @DisplayName("should throw 404 VOTE_NOT_FOUND when Poll is not found")
        void shouldThrowWhenVoteNotFound() {
            when(voteRepository.findByIdWithOptions(voteId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> voteService.getVote(voteId.toString(), null))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND));
        }

        @Test
        @DisplayName("should return Poll")
        void shouldReturnVoteWhenFound() {
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
        @DisplayName("should throw 404 VOTE_NOT_FOUND when Poll does not exist or is disabled")
        void shouldThrowWhenVoteNotActive() {
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.empty());

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND));
        }

        @Test
        @DisplayName("should throw 400 VOTE_MULTIPLE_NOT_ALLOWED when multiple options are sent to single-choice Poll")
        void shouldThrowWhenMultipleChoicesNotAllowed() {
            vote.setActive(true);
            vote.setAllowMultipleChoices(false);
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_MULTIPLE_NOT_ALLOWED));

            verify(userVoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 400 VOTE_OPTION_NOT_IN_VOTE when option does not belong to Poll")
        void shouldThrowWhenOptionBelongsToDifferentVote() {
            vote.setActive(true);
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

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
        @DisplayName("should reject vote with vote.ended when Poll has ended")
        void shouldRejectVoteWhenPollEndedWithVoteEndedCode() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), singleOption(), userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject vote when Poll has not started")
        void shouldRejectVoteWhenPollScheduled() {
            vote.setStartTime(OffsetDateTime.now().plusHours(1));
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), singleOption(), userId.toString(), "127.0.0.1"))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_NOT_ALLOWED));
        }

        @Test
        @DisplayName("should reject removing selection with vote.ended when Poll has ended")
        void shouldRejectRemovalWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.removeVote(voteId.toString(), UUID.randomUUID().toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("should reject withdraw with vote.ended when Poll has ended")
        void shouldRejectRetractionWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.retract(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            verify(userVoteRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("should return myOptionId when viewer has a Ballot")
        void shouldReturnMyOptionIdWhenViewerHasBallot() {
            UUID optionId = UUID.randomUUID();
            setId(option, optionId);
            option.setText("A");
            option.setDisplayOrder(0);
            vote.addOption(option);
            stubConvertToVoteResponse(vote);
            com.vomatt.repository.BallotSelection selection = mock(com.vomatt.repository.BallotSelection.class);
            when(selection.getVoteId()).thenReturn(voteId);
            when(selection.getOptionId()).thenReturn(optionId);
            when(userVoteRepository.findSelections(userId, List.of(voteId))).thenReturn(List.of(selection));

            VoteResponse result = voteService.getVote(voteId.toString(), userId.toString());

            assertThat(result.getMyOptionId()).isEqualTo(optionId.toString());
        }

        @Test
        @DisplayName("should return 403 vote.results.sealed for /results while Poll is Open")
        void shouldReturnForbiddenForResultsWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.getVoteResults(voteId.toString(), null))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_RESULTS_SEALED));
        }

        @Test
        @DisplayName("should return results after Poll has Ended")
        void shouldReturnResultsWhenPollEnded() {
            vote.setEndTime(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId)).thenReturn(List.of());

            assertThat(voteService.getVoteResults(voteId.toString(), null).getTotalParticipants()).isZero();
        }

        @Test
        @DisplayName("should seal option counts on GET detail while Poll is Open")
        void shouldOmitOptionCountsOnGetWhenPollOpen() {
            setId(option, UUID.randomUUID());
            option.setDisplayOrder(0);
            ReflectionTestUtils.setField(option, "voteCount", 5);
            vote.addOption(option);
            stubConvertToVoteResponse(vote);

            VoteResponse result = voteService.getVote(voteId.toString(), null);

            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
            assertThat(result.getParticipantCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("should have null myOptionId when not signed in")
        void shouldReturnNullMyOptionIdWhenSignedOut() {
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
        @DisplayName("should hide voters even from owner when visibility is NOBODY")
        void shouldHideVotersWhenVisibilityIsNobody() {
            endedWith(com.vomatt.entity.VoterVisibility.NOBODY);
            assertHidden(userId.toString());
        }

        @Test
        @DisplayName("should show voters only to owner when visibility is OWNER")
        void shouldShowVotersOnlyToOwnerWhenVisibilityIsOwner() {
            endedWith(com.vomatt.entity.VoterVisibility.OWNER);
            when(userVoteRepository.findVoterPage(eq(voteId), isNull(), isNull(), any())).thenReturn(List.of());

            assertThat(voteService.getVoters(voteId.toString(), userId.toString(), null, null).items()).isEmpty();
            assertHidden(UUID.randomUUID().toString());
        }

        @Test
        @DisplayName("should show voters to any signed-in user when visibility is SIGNED_IN")
        void shouldShowVotersToAnySignedInUserWhenVisibilityIsSignedIn() {
            endedWith(com.vomatt.entity.VoterVisibility.SIGNED_IN);
            when(userVoteRepository.findVoterPage(eq(voteId), isNull(), isNull(), any())).thenReturn(List.of());

            assertThat(voteService.getVoters(voteId.toString(), UUID.randomUUID().toString(), null, null).nextCursor())
                    .isNull();
        }

        @Test
        @DisplayName("should reject viewing voters when Poll has not ended")
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
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
        }

        @Test
        @DisplayName("should reject edit when Poll has started")
        void shouldRejectEditWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), editRequest(null), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_NOT_EDITABLE));
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject an Audience change when Poll is Open")
        void shouldRejectAudienceChangeWhenPollOpen() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            CreateVoteRequest request = editRequest(null);
            request.setAudience(Audience.UNLISTED);

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_NOT_EDITABLE));
            assertThat(vote.getAudience()).isEqualTo(Audience.PUBLIC);
        }

        @Test
        @DisplayName("should change Audience when Scheduled, and keep it when edit omits audience")
        void shouldChangeAudienceWhenScheduledPollEdited() {
            scheduled();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(java.time.Duration.ofDays(365));
            stubSaveAssignsId();
            CreateVoteRequest request = editRequest(null);
            request.setAudience(Audience.UNLISTED);

            assertThat(voteService.updateVote(voteId.toString(), request, userId.toString()).getAudience())
                    .isEqualTo(Audience.UNLISTED);

            request.setAudience(null);
            assertThat(voteService.updateVote(voteId.toString(), request, userId.toString()).getAudience())
                    .isEqualTo(Audience.UNLISTED);
        }

        @Test
        @DisplayName("should return 403 when non-owner edits")
        void shouldRejectEditWhenNotOwner() {
            scheduled();

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), editRequest(null), UUID.randomUUID().toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN));
        }

        @Test
        @DisplayName("should replace title and options when editing Scheduled Poll")
        void shouldReplaceFieldsAndOptionsWhenScheduledPollEdited() {
            scheduled();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(java.time.Duration.ofDays(365));
            stubSaveAssignsId();

            CreateVoteRequest request = editRequest(null);

            VoteResponse result = voteService.updateVote(voteId.toString(), request, userId.toString());

            assertThat(result.getTitle()).isEqualTo("Edited");
            assertThat(result.getEndTime()).isEqualTo(request.getEndTime());
            assertThat(result.getDeadline()).isEqualTo(request.getEndTime());
            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getText)
                    .containsExactlyInAnyOrder("X", "Y", "Z");
            // still Scheduled: counts are Sealed on the edit response too
            assertThat(result.getOptions()).extracting(VoteResponse.VoteOptionResponse::getVotes).containsOnlyNulls();
        }

        @Test
        @DisplayName("should keep original start time when edit omits startTime (edit does not open the Poll)")
        void shouldKeepStartTimeWhenEditOmitsIt() {
            scheduled();
            OffsetDateTime originalStart = vote.getStartTime();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(voteConfig.getMaxVoteDuration()).thenReturn(java.time.Duration.ofDays(365));
            stubSaveAssignsId();
            CreateVoteRequest request = editRequest(null);
            request.setStartTime(null);

            VoteResponse result = voteService.updateVote(voteId.toString(), request, userId.toString());

            assertThat(vote.getStartTime()).isEqualTo(originalStart);
            assertThat(result.isVotingActive()).isFalse();
            assertThat(request.getStartTime()).isNull();
        }

        @Test
        @DisplayName("should validate end time against original start time when edit omits startTime")
        void shouldValidateEndTimeAgainstKeptStartTime() {
            scheduled();
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            CreateVoteRequest request = editRequest(null);
            request.setStartTime(null);
            // ends after now but before the kept start time
            request.setEndTime(vote.getStartTime().minusMinutes(10));

            assertThatThrownBy(() -> voteService.updateVote(voteId.toString(), request, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_END_BEFORE_START));
        }

        @Test
        @DisplayName("should adjust usage_count when editing tags: decrement removed, increment added")
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

    // ─── closeVote ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("closeVote")
    class CloseVoteTests {

        @Test
        @DisplayName("should throw 403 VOTE_FORBIDDEN when non-creator tries to disable")
        void shouldThrowWhenNotCreator() {
            User creator = User.builder().id(UUID.randomUUID()).build(); // different from userId
            vote.setCreator(creator);
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.closeVote(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN));

            assertThat(vote.isActive()).isTrue();
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should end an Open Unlisted Poll now and keep its Deadline when the owner Closes it")
        void shouldCloseWhenOpenUnlisted() {
            OffsetDateTime deadline = OffsetDateTime.now().plusDays(1);
            vote.setStartTime(OffsetDateTime.now().minusHours(1));
            vote.scheduleEnd(deadline);
            vote.setAudience(Audience.UNLISTED);
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.save(vote)).thenReturn(vote);
            stubConvertToVoteResponse(vote);

            voteService.closeVote(voteId.toString(), userId.toString());

            assertThat(vote.getStatus()).isEqualTo(VoteStatus.ENDED);
            assertThat(vote.getEndTime()).isBefore(deadline);
            assertThat(vote.getDeadline()).isEqualTo(deadline);
            verify(voteRepository).save(vote);
        }

        @Test
        @DisplayName("should throw 400 VOTE_CLOSE_PUBLIC_NOT_ALLOWED when the owner Closes an Open Public Poll")
        void shouldThrowWhenClosingPublic() {
            OffsetDateTime deadline = OffsetDateTime.now().plusDays(1);
            vote.setStartTime(OffsetDateTime.now().minusHours(1));
            vote.setEndTime(deadline);
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.closeVote(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_CLOSE_PUBLIC_NOT_ALLOWED));
            assertThat(vote.getEndTime()).isEqualTo(deadline);
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 400 VOTE_CLOSE_SCHEDULED when the owner Closes a Scheduled Poll")
        void shouldThrowWhenClosingScheduled() {
            OffsetDateTime deadline = OffsetDateTime.now().plusDays(2);
            vote.setStartTime(OffsetDateTime.now().plusDays(1));
            vote.setEndTime(deadline);
            vote.setAudience(Audience.UNLISTED);
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.closeVote(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_CLOSE_SCHEDULED));
            assertThat(vote.getEndTime()).isEqualTo(deadline);
            verify(voteRepository, never()).save(any());
        }

        @Test
        @DisplayName("should keep the end time of an Ended Public Poll when the owner Closes it")
        void shouldKeepEndTimeWhenClosingEnded() {
            OffsetDateTime ended = OffsetDateTime.now().minusMinutes(1);
            vote.setStartTime(OffsetDateTime.now().minusDays(1));
            vote.setEndTime(ended);
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.save(vote)).thenReturn(vote);
            stubConvertToVoteResponse(vote);

            voteService.closeVote(voteId.toString(), userId.toString());

            assertThat(vote.getEndTime()).isEqualTo(ended);
        }
    }

    // ─── hasUserVoted ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasUserVoted should delegate to repository")
    void shouldDelegateHasUserVotedToRepository() {
        when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
        when(userVoteRepository.existsByUserIdAndVoteId(userId, voteId)).thenReturn(true);
        assertThat(voteService.hasUserVoted(voteId.toString(), userId.toString())).isTrue();
    }

    // ─── deleteVote ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteVote")
    class DeleteVoteTests {

        private void openPostedMinutesAgo(long minutes) {
            OffsetDateTime posted = OffsetDateTime.now().minusMinutes(minutes);
            ReflectionTestUtils.setField(vote, "createdAt", posted);
            vote.setStartTime(posted);
            vote.setEndTime(posted.plusDays(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
        }

        @Test
        @DisplayName("should throw 403 VOTE_FORBIDDEN when caller is not the owner")
        void shouldThrowForbiddenWhenNotOwner() {
            openPostedMinutesAgo(1);

            assertThatThrownBy(() -> voteService.deleteVote(voteId.toString(), UUID.randomUUID().toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN));
            assertThat(vote.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("should throw 400 VOTE_DELETE_WINDOW_PASSED when an Open Poll was posted too long ago")
        void shouldThrowWhenWindowPassed() {
            openPostedMinutesAgo(21);

            assertThatThrownBy(() -> voteService.deleteVote(voteId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_DELETE_WINDOW_PASSED));
            assertThat(vote.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("should soft-delete and release tag usage when within the window")
        void shouldSoftDeleteWhenWithinWindow() {
            openPostedMinutesAgo(5);
            Tag tag = setId(new Tag(), UUID.randomUUID());
            vote.addTag(tag);

            voteService.deleteVote(voteId.toString(), userId.toString());

            assertThat(vote.getDeletedAt()).isNotNull();
            verify(voteRepository).save(vote);
            verify(tagRepository).decrementUsageCount(Set.of(tag.getId()));
        }

        @Test
        @DisplayName("should return 404 VOTE_NOT_FOUND on every single-Poll path once Deleted")
        void shouldHideDeletedPollWhenLookedUp() {
            vote.setDeletedAt(OffsetDateTime.now());
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdWithOptions(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            String id = voteId.toString();
            String owner = userId.toString();

            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> paths = List.of(
                    () -> voteService.getVote(id, owner),
                    () -> voteService.retract(id, owner),
                    () -> voteService.getVoteResults(id, owner),
                    () -> voteService.getVoters(id, owner, null, null),
                    () -> voteService.hasUserVoted(id, owner),
                    () -> voteService.closeVote(id, owner),
                    () -> voteService.deleteVote(id, owner));
            paths.forEach(path -> assertThatThrownBy(path)
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND)));
        }
    }

    // ─── Invite-only ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Invite-only access and Invitees")
    class InviteOnlyTests {

        private final UUID inviteeId = UUID.randomUUID();

        private void inviteOnly(OffsetDateTime end) {
            vote.setAudience(Audience.INVITE_ONLY);
            vote.setStartTime(OffsetDateTime.now().minusHours(1));
            vote.setEndTime(end);
        }

        private static IdCount count(UUID id, long count) {
            return new IdCount() {
                public UUID getId() { return id; }
                public Long getCount() { return count; }
            };
        }

        @Test
        @DisplayName("should return 404 VOTE_NOT_FOUND on every single-Poll path when the caller is not an Invitee")
        void shouldHideInviteOnlyPollWhenCallerNotInvitee() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdWithOptions(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdForShare(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            String id = voteId.toString();
            String stranger = UUID.randomUUID().toString();
            VoteRequest ballot = new VoteRequest();
            ballot.setOptionIds(List.of(UUID.randomUUID().toString()));
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(UUID.randomUUID()));

            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> paths = List.of(
                    () -> voteService.getVote(id, null),
                    () -> voteService.getVote(id, stranger),
                    () -> voteService.vote(id, ballot, stranger, null),
                    () -> voteService.retract(id, stranger),
                    () -> voteService.getVoteResults(id, stranger),
                    () -> voteService.getVoters(id, stranger, null, null),
                    () -> voteService.hasUserVoted(id, stranger),
                    () -> voteService.getInvitees(id, stranger),
                    () -> voteService.addInvitees(id, invite, stranger),
                    () -> voteService.removeInvitee(id, stranger, stranger),
                    () -> voteService.closeVote(id, stranger),
                    () -> voteService.deleteVote(id, stranger));
            paths.forEach(path -> assertThatThrownBy(path)
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND)));
        }

        @Test
        @DisplayName("should show the Poll with inviteeCount when the caller is an Invitee")
        void shouldShowPollWithInviteeCountWhenCallerIsInvitee() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdWithOptions(voteId)).thenReturn(Optional.of(vote));
            when(inviteeRepository.existsByVoteIdAndUserId(voteId, inviteeId)).thenReturn(true);
            when(inviteeRepository.countByVoteIds(List.of(voteId))).thenReturn(List.of(count(voteId, 3)));

            VoteResponse response = voteService.getVote(voteId.toString(), inviteeId.toString());

            assertThat(response.getInviteeCount()).isEqualTo(3L);
            assertThat(response.getDeletable()).isNull();
        }

        @Test
        @DisplayName("should show voters to an Invitee when visibility is SIGNED_IN on an Ended Invite-only Poll")
        void shouldShowVotersToInviteeWhenVisibilitySignedIn() {
            inviteOnly(OffsetDateTime.now().minusMinutes(1));
            vote.setVoterVisibility(com.vomatt.entity.VoterVisibility.SIGNED_IN);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(inviteeRepository.existsByVoteIdAndUserId(voteId, inviteeId)).thenReturn(true);
            when(userVoteRepository.findVoterPage(eq(voteId), isNull(), isNull(), any())).thenReturn(List.of());

            assertThat(voteService.getVoters(voteId.toString(), inviteeId.toString(), null, null).items()).isEmpty();
        }

        @Test
        @DisplayName("should throw 403 VOTE_FORBIDDEN when an Invitee manages Invitees")
        void shouldForbidInviteeManagementWhenCallerIsInvitee() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(inviteeRepository.existsByVoteIdAndUserId(voteId, inviteeId)).thenReturn(true);
            String id = voteId.toString();
            String invitee = inviteeId.toString();
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(UUID.randomUUID()));

            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> paths = List.of(
                    () -> voteService.getInvitees(id, invitee),
                    () -> voteService.addInvitees(id, invite, invitee),
                    () -> voteService.removeInvitee(id, invitee, invitee));
            paths.forEach(path -> assertThatThrownBy(path)
                    .satisfies(ex -> assertApiException(ex, HttpStatus.FORBIDDEN, MessageKey.VOTE_FORBIDDEN)));
        }

        @Test
        @DisplayName("should throw 400 VOTE_INVITEE_HAS_BALLOT and keep the Invitee when they hold a Ballot")
        void shouldRefuseRemovalWhenInviteeHoldsBallot() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            Invitee invitee = new Invitee(vote, User.builder().id(inviteeId).build());
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(inviteeRepository.findByVoteIdAndUserId(voteId, inviteeId)).thenReturn(Optional.of(invitee));
            when(userVoteRepository.existsByUserIdAndVoteId(inviteeId, voteId)).thenReturn(true);

            assertThatThrownBy(() -> voteService.removeInvitee(voteId.toString(), inviteeId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_INVITEE_HAS_BALLOT));
            verify(inviteeRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should remove the Invitee when they hold no Ballot")
        void shouldRemoveInviteeWhenNoBallot() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            Invitee invitee = new Invitee(vote, User.builder().id(inviteeId).build());
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(inviteeRepository.findByVoteIdAndUserId(voteId, inviteeId)).thenReturn(Optional.of(invitee));

            assertThat(voteService.removeInvitee(voteId.toString(), inviteeId.toString(), userId.toString())).isEmpty();
            verify(inviteeRepository).delete(invitee);
        }

        @Test
        @DisplayName("should throw 400 VOTE_ENDED when Invitees change after the Poll Ended")
        void shouldRefuseInviteeChangesWhenPollEnded() {
            inviteOnly(OffsetDateTime.now().minusMinutes(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(UUID.randomUUID()));

            assertThatThrownBy(() -> voteService.addInvitees(voteId.toString(), invite, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
            assertThatThrownBy(() -> voteService.removeInvitee(voteId.toString(), inviteeId.toString(), userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_ENDED));
        }

        @Test
        @DisplayName("should throw 400 VOTE_INVITEE_NOT_INVITE_ONLY when adding Invitees to a Public Poll")
        void shouldRefuseAddWhenPollNotInviteOnly() {
            vote.setEndTime(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(UUID.randomUUID()));

            assertThatThrownBy(() -> voteService.addInvitees(voteId.toString(), invite, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_INVITEE_NOT_INVITE_ONLY));
        }

        @Test
        @DisplayName("should throw 400 VOTE_INVITEE_IDS_INVALID when an invited user does not exist")
        void shouldRefuseAddWhenUserUnknown() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findAllById(any())).thenReturn(List.of());
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(UUID.randomUUID()));

            assertThatThrownBy(() -> voteService.addInvitees(voteId.toString(), invite, userId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.BAD_REQUEST, MessageKey.VOTE_INVITEE_IDS_INVALID));
            verify(inviteeRepository, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("should skip users already invited when adding Invitees")
        void shouldSkipExistingInviteesWhenAdding() {
            inviteOnly(OffsetDateTime.now().plusDays(1));
            User bob = User.builder().id(inviteeId).build();
            User carol = User.builder().id(UUID.randomUUID()).build();
            when(voteRepository.findByIdForUpdate(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findAllById(Set.of(bob.getId(), carol.getId()))).thenReturn(List.of(bob, carol));
            when(inviteeRepository.findByVoteId(voteId)).thenReturn(List.of(new Invitee(vote, bob)));
            InviteesRequest invite = new InviteesRequest();
            invite.setUserIds(List.of(bob.getId(), carol.getId()));

            voteService.addInvitees(voteId.toString(), invite, userId.toString());

            verify(inviteeRepository).saveAllAndFlush(argThat(invitees ->
                    invitees instanceof List<?> list && list.size() == 1
                            && ((Invitee) list.getFirst()).getUser() == carol));
        }
    }

    // ─── createVote with tags ─────────────────────────────────────────────────

    @Nested
    @DisplayName("createVote with tags")
    class CreateVoteWithTags {

        @Test
        @DisplayName("should link tags and update usageCount when creating Poll")
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
        @DisplayName("should throw 400 TAG_IDS_INVALID when tagIds contains nonexistent ID")
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
        @DisplayName("should create Poll normally without tagIds")
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

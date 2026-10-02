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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock VoteMapper voteMapper;

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

    private VoteResponse stubConvertToVoteResponse(Vote v) {
        when(voteRepository.findByIdWithOptions(v.getId())).thenReturn(Optional.of(v));
        when(userVoteRepository.countByOptionGroupedForVote(v.getId()))
                .thenReturn(Collections.emptyList());
        VoteResponse response = new VoteResponse();
        when(voteMapper.toResponse(eq(v), any(), any(), eq(0L))).thenReturn(response);
        return response;
    }

    private void stubSaveAssignsId() {
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
            Vote v = inv.getArgument(0);
            if (v.getId() == null) {
                setId(v, UUID.randomUUID()); // 模擬 DB 分配 ID
            }
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

            VoteResponse expected = new VoteResponse();
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(expected);

            VoteResponse result = voteService.createVote(request, userId.toString());

            assertThat(result).isSameAs(expected);
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
            assertThatThrownBy(() -> voteService.getVote(voteId.toString()))
                    .satisfies(ex -> assertApiException(ex, HttpStatus.NOT_FOUND, MessageKey.VOTE_NOT_FOUND));
        }

        @Test
        @DisplayName("應該成功回傳投票")
        void shouldReturnVoteWhenFound() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            VoteResponse expected = stubConvertToVoteResponse(vote);

            VoteResponse result = voteService.getVote(voteId.toString());

            assertThat(result).isSameAs(expected);
        }
    }

    // ─── vote ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("vote")
    class CastVoteTests {

        @Test
        @DisplayName("應該在投票不存在或已停用時拋出 404 VOTE_NOT_FOUND")
        void shouldThrowWhenVoteNotActive() {
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.empty());

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
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

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
            vote.setAllowMultipleChoices(true);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

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
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

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
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(new VoteResponse());

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

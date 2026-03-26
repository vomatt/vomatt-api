package com.vomattapi.service;

import com.vomattapi.application.dto.vote.CreateVoteRequest;
import com.vomattapi.application.dto.vote.VoteRequest;
import com.vomattapi.application.dto.vote.VoteResponse;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.exception.VotingNotAllowedException;
import com.vomattapi.application.mapper.VoteMapper;
import com.vomattapi.application.service.vote.VoteServiceImpl;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.vote.Tag;
import com.vomattapi.domain.vote.UserVote;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteOption;
import com.vomattapi.domain.vote.repository.TagRepository;
import com.vomattapi.domain.vote.repository.UserVoteRepository;
import com.vomattapi.domain.vote.repository.VoteOptionRepository;
import com.vomattapi.domain.vote.event.VoteCreatedEvent;
import com.vomattapi.domain.vote.event.VoteDeactivatedEvent;
import com.vomattapi.domain.vote.repository.VoteRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.infrastructure.config.VoteConfigurationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalDateTime;
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
@DisplayName("VoteServiceImpl")
class VoteServiceImplTest {

    @Mock VoteRepository voteRepository;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock UserVoteRepository userVoteRepository;
    @Mock UserRepository userRepository;
    @Mock TagRepository tagRepository;
    @Mock VoteConfigurationProperties voteConfig;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock VoteMapper voteMapper;

    @InjectMocks
    VoteServiceImpl voteService;

    private UUID userId;
    private UUID voteId;
    private User user;
    private Vote vote;
    private VoteOption option;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        voteId = UUID.randomUUID();

        user = new User();
        user.setUsername("testuser");

        vote = new Vote();
        vote.setTitle("Test Vote");

        option = new VoteOption();
        option.setVote(vote);
    }

    // ─── helper ───────────────────────────────────────────────────────────────

    private VoteResponse stubConvertToVoteResponse(Vote v) {
        when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(v.getId()))
                .thenReturn(Collections.emptyList());
        when(userVoteRepository.countByOptionGroupedForVote(v.getId()))
                .thenReturn(Collections.emptyList());
        when(userVoteRepository.countByVoteId(v.getId())).thenReturn(0L);
        VoteResponse response = new VoteResponse();
        when(voteMapper.toResponse(eq(v), any(), any(), eq(0L))).thenReturn(response);
        return response;
    }

    // ─── createVote ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createVote")
    class CreateVoteTests {

        @Test
        @DisplayName("應該在選項數量不足時拋出 BusinessRuleViolationException")
        void shouldThrowWhenTooFewOptions() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("Minimum 2 options required");
        }

        @Test
        @DisplayName("應該在選項數量超過上限時拋出 BusinessRuleViolationException")
        void shouldThrowWhenTooManyOptions() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(2);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B"), buildOption("C")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("Maximum 2 options allowed");
        }

        @Test
        @DisplayName("應該在結束時間已過去時拋出 BusinessRuleViolationException")
        void shouldThrowWhenEndTimeInPast() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setEndTime(LocalDateTime.now().minusDays(1));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("End time cannot be in the past");
        }

        @Test
        @DisplayName("應該在找不到建立者時拋出 EntityNotFoundException")
        void shouldThrowWhenCreatorNotFound() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Test");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("應該成功建立投票並發布事件")
        void shouldCreateVoteAndPublishEvent() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
                Vote v = inv.getArgument(0);
                v.setId(UUID.randomUUID()); // 模擬 DB 分配 ID
                return v;
            });

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Favourite Color?");
            request.setOptions(List.of(buildOption("Red"), buildOption("Blue")));

            VoteResponse expected = new VoteResponse();
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
            when(voteMapper.toResponse(any(), any(), any(), anyLong())).thenReturn(expected);

            VoteResponse result = voteService.createVote(request, userId.toString());

            assertThat(result).isSameAs(expected);
            verify(eventPublisher).publishEvent(any(VoteCreatedEvent.class));
        }
    }

    // ─── getVote ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getVote")
    class GetVoteTests {

        @Test
        @DisplayName("應該在找不到投票時拋出 VoteNotFoundException")
        void shouldThrowWhenVoteNotFound() {
            when(voteRepository.findById(voteId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> voteService.getVote(voteId.toString()))
                    .isInstanceOf(VoteNotFoundException.class);
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
        @DisplayName("應該在投票不在進行中時拋出 VotingNotAllowedException")
        void shouldThrowWhenVoteNotActive() {
            Vote inactiveVote = new Vote();
            inactiveVote.setActive(false);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.empty());

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(UUID.randomUUID().toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .isInstanceOf(VoteNotFoundException.class);
        }

        @Test
        @DisplayName("應該在多選投票傳入多個選項時拋出 VotingNotAllowedException")
        void shouldThrowWhenMultipleChoicesNotAllowed() {
            vote.setActive(true);
            vote.setAllowMultipleChoices(false);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            VoteRequest request = new VoteRequest();
            UUID optId1 = UUID.randomUUID();
            UUID optId2 = UUID.randomUUID();
            request.setOptionIds(List.of(optId1.toString(), optId2.toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .isInstanceOf(VotingNotAllowedException.class)
                    .hasMessageContaining("Multiple choices not allowed");
        }

        @Test
        @DisplayName("應該在選項不屬於該投票時拋出 BusinessRuleViolationException")
        void shouldThrowWhenOptionBelongsToDifferentVote() {
            vote.setActive(true);
            vote.setAllowMultipleChoices(true);
            when(voteRepository.findByIdAndIsActiveTrue(voteId)).thenReturn(Optional.of(vote));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UUID optionId = UUID.randomUUID();
            Vote differentVote = new Vote();
            differentVote.setId(UUID.randomUUID()); // different from voteId
            option.setVote(differentVote);
            when(voteOptionRepository.findById(optionId)).thenReturn(Optional.of(option));

            VoteRequest request = new VoteRequest();
            request.setOptionIds(List.of(optionId.toString()));

            assertThatThrownBy(() -> voteService.vote(voteId.toString(), request, userId.toString(), "127.0.0.1"))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("does not belong to this vote");
        }
    }

    // ─── deactivateVote ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivateVote")
    class DeactivateVoteTests {

        @Test
        @DisplayName("應該在非建立者嘗試停用時拋出 UnauthorizedOperationException")
        void shouldThrowWhenNotCreator() {
            User creator = new User();
            creator.setId(UUID.randomUUID()); // different from userId
            vote.setCreator(creator);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));

            assertThatThrownBy(() -> voteService.deactivateVote(voteId.toString(), userId.toString()))
                    .isInstanceOf(UnauthorizedOperationException.class);
        }

        @Test
        @DisplayName("建立者應該能成功停用投票")
        void shouldDeactivateWhenCreator() {
            User creator = new User();
            creator.setId(userId);
            vote.setId(voteId);
            vote.setCreator(creator);
            vote.setActive(true);
            when(voteRepository.findById(voteId)).thenReturn(Optional.of(vote));
            when(voteRepository.save(vote)).thenReturn(vote);
            stubConvertToVoteResponse(vote);

            voteService.deactivateVote(voteId.toString(), userId.toString());

            assertThat(vote.isActive()).isFalse();
            verify(eventPublisher).publishEvent(any(VoteDeactivatedEvent.class));
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
            when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
                Vote v = inv.getArgument(0);
                v.setId(UUID.randomUUID());
                return v;
            });

            UUID tagId = UUID.randomUUID();
            Tag tag = new Tag("Tech", "tech", "Technology", 1);
            tag.setId(tagId);
            when(tagRepository.findAllByIdIn(Set.of(tagId))).thenReturn(List.of(tag));
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
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
        @DisplayName("應該在 tagIds 包含不存在的 ID 時拋出異常")
        void shouldThrowWhenTagIdNotFound() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            // Only return one tag when two are requested
            Tag tag = new Tag("Tech", "tech", "Technology", 1);
            tag.setId(tagId1);
            when(tagRepository.findAllByIdIn(Set.of(tagId1, tagId2))).thenReturn(List.of(tag));

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Tag Vote");
            request.setOptions(List.of(buildOption("A"), buildOption("B")));
            request.setTagIds(List.of(tagId1, tagId2));

            assertThatThrownBy(() -> voteService.createVote(request, userId.toString()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("部分標籤 ID 不存在");
        }

        @Test
        @DisplayName("應該在不帶 tagIds 時正常建立投票")
        void shouldCreateVoteWithoutTags() {
            when(voteConfig.getMinOptionsPerVote()).thenReturn(2);
            when(voteConfig.getMaxOptionsPerVote()).thenReturn(10);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> {
                Vote v = inv.getArgument(0);
                v.setId(UUID.randomUUID());
                return v;
            });
            when(voteOptionRepository.findByVoteIdOrderByDisplayOrder(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByOptionGroupedForVote(any())).thenReturn(Collections.emptyList());
            when(userVoteRepository.countByVoteId(any())).thenReturn(0L);
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

    // ─── helpers ──────────────────────────────────────────────────────────────

    private CreateVoteRequest.VoteOptionRequest buildOption(String text) {
        CreateVoteRequest.VoteOptionRequest opt = new CreateVoteRequest.VoteOptionRequest();
        opt.setText(text);
        return opt;
    }
}

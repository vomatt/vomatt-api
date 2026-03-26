package com.vomattapi.application.service.vote;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.application.dto.vote.CreateVoteRequest;
import com.vomattapi.application.dto.vote.VoteRequest;
import com.vomattapi.application.dto.vote.VoteResponse;
import com.vomattapi.application.dto.vote.VoteResultResponse;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.exception.VotingNotAllowedException;
import com.vomattapi.application.mapper.VoteMapper;
import com.vomattapi.application.service.vote.VoteService;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.vote.Tag;
import com.vomattapi.domain.vote.UserVote;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteOption;
import com.vomattapi.domain.vote.repository.TagRepository;
import com.vomattapi.domain.vote.repository.UserVoteRepository;
import com.vomattapi.domain.vote.repository.VoteOptionRepository;
import com.vomattapi.domain.vote.repository.VoteRepository;
import com.vomattapi.domain.vote.event.VoteCastEvent;
import com.vomattapi.domain.vote.event.VoteCreatedEvent;
import com.vomattapi.domain.vote.event.VoteDeactivatedEvent;
import com.vomattapi.infrastructure.config.VoteConfigurationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class VoteServiceImpl implements VoteService {

    private final VoteRepository voteRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final UserVoteRepository userVoteRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final VoteConfigurationProperties voteConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final VoteMapper voteMapper;

    @Override
    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        validateCreateVoteRequest(request);

        User creator = userRepository.findById(UUID.fromString(creatorId))
            .orElseThrow(() -> new EntityNotFoundException("User", creatorId));

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : LocalDateTime.now());
        vote.setAllowMultipleChoices(request.isAllowMultipleChoices());
        vote.setAnonymous(request.isAnonymous());

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            Set<UUID> tagUUIDs = new HashSet<>(request.getTagIds());
            List<Tag> tags = tagRepository.findAllByIdIn(tagUUIDs);
            if (tags.size() != tagUUIDs.size()) {
                throw new BusinessRuleViolationException("部分標籤 ID 不存在");
            }
            tags.forEach(vote::addTag);
        }

        vote = voteRepository.save(vote);

        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }

        vote = voteRepository.save(vote);
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tagRepository.incrementUsageCount(new HashSet<>(request.getTagIds()));
        }

        eventPublisher.publishEvent(new VoteCreatedEvent(
            vote.getId().toString(), creatorId, vote.getTitle(), vote.getDescription(), vote.getOptions().size()));

        return convertToVoteResponse(vote);
    }

    @Override
    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId));
        return convertToVoteResponse(vote);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoteResponse> getActiveVotes() {
        List<Vote> votes = voteRepository.findActiveVotesAtTime(LocalDateTime.now());
        return votes.stream()
            .map(this::convertToVoteResponse)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotes(Pageable pageable) {
        Page<Vote> votes = voteRepository.findActiveVotesAtTime(LocalDateTime.now(), pageable);
        return votes.map(this::convertToVoteResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoteResponse> getActiveVotesByTag(String tagSlug, Pageable pageable) {
        Page<Vote> votes = voteRepository.findByTagSlugAndIsActiveTrue(tagSlug, pageable);
        return votes.map(this::convertToVoteResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoteResponse> getVotesByCreator(String creatorId) {
        List<Vote> votes = voteRepository.findByCreatorIdOrderByCreatedAtDesc(UUID.fromString(creatorId));
        return votes.stream()
            .map(this::convertToVoteResponse)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoteResponse> getVotesByCreator(String creatorId, Pageable pageable) {
        Page<Vote> votes = voteRepository.findByCreatorIdOrderByCreatedAtDesc(UUID.fromString(creatorId), pageable);
        return votes.map(this::convertToVoteResponse);
    }

    @Override
    public VoteResponse vote(String voteId, VoteRequest request, String userId, String ipAddress) {
        Vote vote = voteRepository.findByIdAndIsActiveTrue(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.isVotingActive()) {
            throw new VotingNotAllowedException(voteId, "Voting period has ended or not started");
        }

        User user = userRepository.findById(UUID.fromString(userId))
            .orElseThrow(() -> new EntityNotFoundException("User", userId));

        UUID userUuid = UUID.fromString(userId);
        UUID voteUuid = UUID.fromString(voteId);

        if (!vote.isAllowMultipleChoices()) {
            userVoteRepository.deleteByUserIdAndVoteId(userUuid, voteUuid);
            if (request.getOptionIds().size() > 1) {
                throw new VotingNotAllowedException("Multiple choices not allowed for this vote");
            }
        }

        for (String optionId : request.getOptionIds()) {
            UUID optionUuid = UUID.fromString(optionId);
            VoteOption option = voteOptionRepository.findById(optionUuid)
                .orElseThrow(() -> new EntityNotFoundException("VoteOption", optionId));

            if (!option.getVote().getId().toString().equals(voteId)) {
                throw new BusinessRuleViolationException("Option does not belong to this vote");
            }

            if (!userVoteRepository.existsByUserIdAndVoteIdAndOptionId(userUuid, voteUuid, optionUuid)) {
                UserVote userVote = new UserVote(user, vote, option, ipAddress);
                userVoteRepository.save(userVote);
            }
        }

        log.info("User {} voted on vote {} with options {}", userId, voteId, request.getOptionIds());

        eventPublisher.publishEvent(new VoteCastEvent(voteId, userId, request.getOptionIds(), ipAddress));

        return convertToVoteResponse(voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId)));
    }

    @Override
    public VoteResponse removeVote(String voteId, String optionId, String userId) {
        Vote vote = voteRepository.findByIdAndIsActiveTrue(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.isVotingActive()) {
            throw new VotingNotAllowedException(voteId, "Voting period has ended or not started");
        }

        userVoteRepository.deleteByUserIdAndVoteIdAndOptionId(
            UUID.fromString(userId), UUID.fromString(voteId), UUID.fromString(optionId));
        log.info("User {} removed vote from option {} in vote {}", userId, optionId, voteId);

        return convertToVoteResponse(voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId)));
    }

    @Override
    @Transactional(readOnly = true)
    public VoteResultResponse getVoteResults(String voteId) {
        UUID voteUuid = UUID.fromString(voteId);
        Vote vote = voteRepository.findById(voteUuid)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        int totalParticipants = (int) userVoteRepository.countDistinctUserByVoteId(voteUuid);
        long totalVoteCount = userVoteRepository.countByVoteId(voteUuid);
        Map<UUID, Long> optionCounts = buildOptionCountMap(voteUuid);
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteUuid);

        return voteMapper.toResultResponse(vote, options, totalParticipants, optionCounts, totalVoteCount);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUserVoted(String voteId, String userId) {
        return userVoteRepository.existsByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getUserVoteOptions(String voteId, String userId) {
        return userVoteRepository.findByUserIdAndVoteId(UUID.fromString(userId), UUID.fromString(voteId))
            .stream()
            .map(mv -> mv.getOption().getId().toString())
            .collect(Collectors.toList());
    }

    @Override
    public VoteResponse deactivateVote(String voteId, String creatorId) {
        Vote vote = voteRepository.findById(UUID.fromString(voteId))
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.getCreator().getId().toString().equals(creatorId)) {
            throw new UnauthorizedOperationException("deactivate", "vote");
        }

        vote.deactivate();
        vote = voteRepository.save(vote);
        log.info("Vote {} deactivated by creator {}", voteId, creatorId);

        eventPublisher.publishEvent(new VoteDeactivatedEvent(voteId, creatorId, "Manual deactivation by creator"));

        return convertToVoteResponse(vote);
    }

    @Override
    public void processExpiredVotes() {
        List<Vote> expiredVotes = voteRepository.findExpiredActiveVotes(LocalDateTime.now());
        for (Vote vote : expiredVotes) {
            vote.deactivate();
        }
        if (!expiredVotes.isEmpty()) {
            voteRepository.saveAll(expiredVotes);
            log.info("Processed {} expired votes", expiredVotes.size());
        }
    }

    private VoteResponse convertToVoteResponse(Vote vote) {
        UUID voteId = vote.getId();
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId);
        Map<UUID, Long> optionCounts = buildOptionCountMap(voteId);
        long totalVoteCount = userVoteRepository.countByVoteId(voteId);
        return voteMapper.toResponse(vote, options, optionCounts, totalVoteCount);
    }

    private Map<UUID, Long> buildOptionCountMap(UUID voteId) {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : userVoteRepository.countByOptionGroupedForVote(voteId)) {
            map.put((UUID) row[0], (Long) row[1]);
        }
        return map;
    }

    private void validateCreateVoteRequest(CreateVoteRequest request) {
        if (request.getOptions().size() < voteConfig.getMinOptionsPerVote()) {
            throw new BusinessRuleViolationException(
                String.format("Minimum %d options required", voteConfig.getMinOptionsPerVote()));
        }

        if (request.getOptions().size() > voteConfig.getMaxOptionsPerVote()) {
            throw new BusinessRuleViolationException(
                String.format("Maximum %d options allowed", voteConfig.getMaxOptionsPerVote()));
        }

        if (request.getEndTime() != null) {
            if (request.getEndTime().isBefore(LocalDateTime.now())) {
                throw new BusinessRuleViolationException("End time cannot be in the past");
            }

            if (request.getStartTime() != null && request.getEndTime().isBefore(request.getStartTime())) {
                throw new BusinessRuleViolationException("End time cannot be before start time");
            }

            LocalDateTime maxEndTime = (request.getStartTime() != null ? request.getStartTime() : LocalDateTime.now())
                .plus(voteConfig.getMaxVoteDuration());
            if (request.getEndTime().isAfter(maxEndTime)) {
                throw new BusinessRuleViolationException(
                    String.format("Vote duration cannot exceed %d days", voteConfig.getMaxVoteDuration().toDays()));
            }
        }
    }
}

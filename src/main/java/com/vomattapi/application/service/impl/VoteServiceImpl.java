package com.vomattapi.application.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomattapi.application.dto.request.CreateVoteRequest;
import com.vomattapi.application.dto.request.VoteRequest;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.exception.VotingNotAllowedException;
import com.vomattapi.application.service.VoteService;
import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.repository.MemberRepository;
import com.vomattapi.domain.vote.MemberVote;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteOption;
import com.vomattapi.domain.vote.repository.MemberVoteRepository;
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
    private final MemberVoteRepository memberVoteRepository;
    private final MemberRepository memberRepository;
    private final VoteConfigurationProperties voteConfig;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public VoteResponse createVote(CreateVoteRequest request, String creatorId) {
        // Validate request
        validateCreateVoteRequest(request);
        
        Member creator = memberRepository.findById(creatorId)
            .orElseThrow(() -> new EntityNotFoundException("Member", creatorId));

        Vote vote = new Vote(request.getTitle(), request.getDescription(), creator, request.getEndTime());
        vote.setStartTime(request.getStartTime() != null ? request.getStartTime() : LocalDateTime.now());
        vote.setAllowMultipleChoices(request.isAllowMultipleChoices());
        vote.setAnonymous(request.isAnonymous());

        vote = voteRepository.save(vote);

        for (int i = 0; i < request.getOptions().size(); i++) {
            CreateVoteRequest.VoteOptionRequest optionRequest = request.getOptions().get(i);
            VoteOption option = new VoteOption(optionRequest.getText(), optionRequest.getDescription(), vote);
            option.setDisplayOrder(optionRequest.getDisplayOrder() != null ? optionRequest.getDisplayOrder() : i);
            vote.addOption(option);
        }

        vote = voteRepository.save(vote);
        log.info("Vote created: {} by user: {}", vote.getId(), creatorId);
        
        // Publish event
        eventPublisher.publishEvent(new VoteCreatedEvent(
            vote.getId(), creatorId, vote.getTitle(), vote.getDescription(), vote.getOptions().size()));
        
        return convertToVoteResponse(vote);
    }

    @Override
    @Transactional(readOnly = true)
    public VoteResponse getVote(String voteId) {
        Vote vote = voteRepository.findById(voteId)
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
    public List<VoteResponse> getVotesByCreator(String creatorId) {
        List<Vote> votes = voteRepository.findByCreatorIdOrderByCreatedAtDesc(creatorId);
        return votes.stream()
            .map(this::convertToVoteResponse)
            .collect(Collectors.toList());
    }

    @Override
    public VoteResponse vote(String voteId, VoteRequest request, String memberId, String ipAddress) {
        Vote vote = voteRepository.findByIdAndIsActiveTrue(voteId)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.isVotingActive()) {
            throw new VotingNotAllowedException(voteId, "Voting period has ended or not started");
        }

        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new EntityNotFoundException("Member", memberId));

        if (!vote.isAllowMultipleChoices()) {
            memberVoteRepository.deleteByMemberIdAndVoteId(memberId, voteId);
            if (request.getOptionIds().size() > 1) {
                throw new VotingNotAllowedException("Multiple choices not allowed for this vote");
            }
        }

        for (String optionId : request.getOptionIds()) {
            VoteOption option = voteOptionRepository.findById(optionId)
                .orElseThrow(() -> new EntityNotFoundException("VoteOption", optionId));

            if (!option.getVote().getId().equals(voteId)) {
                throw new BusinessRuleViolationException("Option does not belong to this vote");
            }

            if (!memberVoteRepository.existsByMemberIdAndVoteIdAndOptionId(memberId, voteId, optionId)) {
                MemberVote memberVote = new MemberVote(member, vote, option, ipAddress);
                memberVoteRepository.save(memberVote);
            }
        }

        log.info("Member {} voted on vote {} with options {}", memberId, voteId, request.getOptionIds());
        
        // Publish event
        eventPublisher.publishEvent(new VoteCastEvent(voteId, memberId, request.getOptionIds(), ipAddress));
        
        return convertToVoteResponse(voteRepository.findById(voteId).get());
    }

    @Override
    public VoteResponse removeVote(String voteId, String optionId, String memberId) {
        Vote vote = voteRepository.findByIdAndIsActiveTrue(voteId)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.isVotingActive()) {
            throw new VotingNotAllowedException(voteId, "Voting period has ended or not started");
        }

        memberVoteRepository.deleteByMemberIdAndVoteIdAndOptionId(memberId, voteId, optionId);
        log.info("Member {} removed vote from option {} in vote {}", memberId, optionId, voteId);
        
        return convertToVoteResponse(voteRepository.findById(voteId).get());
    }

    @Override
    @Transactional(readOnly = true)
    public VoteResultResponse getVoteResults(String voteId) {
        Vote vote = voteRepository.findById(voteId)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        VoteResultResponse response = new VoteResultResponse();
        response.setId(vote.getId());
        response.setTitle(vote.getTitle());
        response.setDescription(vote.getDescription());
        response.setCreatorId(vote.getCreator().getId());
        response.setCreatorUsername(vote.getCreator().getUsername());
        response.setStartTime(vote.getStartTime());
        response.setEndTime(vote.getEndTime());
        response.setActive(vote.isActive());
        response.setAllowMultipleChoices(vote.isAllowMultipleChoices());
        response.setAnonymous(vote.isAnonymous());
        response.setCreatedAt(vote.getCreatedAt());
        response.setTotalVotes(vote.getTotalVotes());
        response.setVotingActive(vote.isVotingActive());

        List<String> uniqueVoterIds = memberVoteRepository.findByVoteId(voteId).stream()
            .map(mv -> mv.getMember().getId())
            .distinct()
            .collect(Collectors.toList());
        response.setTotalParticipants(uniqueVoterIds.size());

        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(voteId);
        List<VoteResultResponse.VoteOptionResultResponse> optionResults = new ArrayList<>();

        for (VoteOption option : options) {
            VoteResultResponse.VoteOptionResultResponse optionResult = new VoteResultResponse.VoteOptionResultResponse();
            optionResult.setId(option.getId());
            optionResult.setText(option.getText());
            optionResult.setDescription(option.getDescription());
            optionResult.setDisplayOrder(option.getDisplayOrder());
            optionResult.setVoteCount(option.getVoteCount());
            
            double percentage = response.getTotalVotes() > 0 ? 
                (double) option.getVoteCount() / response.getTotalVotes() * 100 : 0.0;
            optionResult.setPercentage(percentage);

            if (!vote.isAnonymous()) {
                List<VoteResultResponse.VoterResponse> voters = option.getMemberVotes().stream()
                    .map(mv -> {
                        VoteResultResponse.VoterResponse voter = new VoteResultResponse.VoterResponse();
                        voter.setMemberId(mv.getMember().getId());
                        voter.setUsername(mv.getMember().getUsername());
                        voter.setVotedAt(mv.getVotedAt());
                        return voter;
                    })
                    .collect(Collectors.toList());
                optionResult.setVoters(voters);
            } else {
                optionResult.setVoters(new ArrayList<>());
            }

            optionResults.add(optionResult);
        }

        response.setOptions(optionResults);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUserVoted(String voteId, String memberId) {
        return memberVoteRepository.existsByMemberIdAndVoteId(memberId, voteId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getUserVoteOptions(String voteId, String memberId) {
        return memberVoteRepository.findByMemberIdAndVoteId(memberId, voteId)
            .stream()
            .map(mv -> mv.getOption().getId())
            .collect(Collectors.toList());
    }

    @Override
    public VoteResponse deactivateVote(String voteId, String creatorId) {
        Vote vote = voteRepository.findById(voteId)
            .orElseThrow(() -> new VoteNotFoundException(voteId));

        if (!vote.getCreator().getId().equals(creatorId)) {
            throw new UnauthorizedOperationException("deactivate", "vote");
        }

        vote.deactivate();
        vote = voteRepository.save(vote);
        log.info("Vote {} deactivated by creator {}", voteId, creatorId);
        
        // Publish event
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
        VoteResponse response = new VoteResponse();
        response.setId(vote.getId());
        response.setTitle(vote.getTitle());
        response.setDescription(vote.getDescription());
        response.setCreatorId(vote.getCreator().getId());
        response.setCreatorUsername(vote.getCreator().getUsername());
        response.setStartTime(vote.getStartTime());
        response.setEndTime(vote.getEndTime());
        response.setActive(vote.isActive());
        response.setAllowMultipleChoices(vote.isAllowMultipleChoices());
        response.setAnonymous(vote.isAnonymous());
        response.setCreatedAt(vote.getCreatedAt());
        response.setUpdatedAt(vote.getUpdatedAt());
        response.setTotalVotes(vote.getTotalVotes());
        response.setVotingActive(vote.isVotingActive());

        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(vote.getId());
        List<VoteResponse.VoteOptionResponse> optionResponses = options.stream()
            .map(option -> {
                VoteResponse.VoteOptionResponse optionResponse = new VoteResponse.VoteOptionResponse();
                optionResponse.setId(option.getId());
                optionResponse.setText(option.getText());
                optionResponse.setDescription(option.getDescription());
                optionResponse.setDisplayOrder(option.getDisplayOrder());
                optionResponse.setCreatedAt(option.getCreatedAt());
                optionResponse.setVoteCount(option.getVoteCount());
                return optionResponse;
            })
            .collect(Collectors.toList());
        
        response.setOptions(optionResponses);
        return response;
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
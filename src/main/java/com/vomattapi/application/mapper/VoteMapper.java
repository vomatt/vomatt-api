package com.vomattapi.application.mapper;

import com.vomattapi.application.dto.tag.TagDto;
import com.vomattapi.application.dto.vote.VoteResponse;
import com.vomattapi.application.dto.vote.VoteResultResponse;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class VoteMapper {

    /**
     * @param optionCounts Pre-fetched Map<optionId, voteCount> to avoid N+1 lazy load
     * @param totalVoteCount Pre-fetched total vote count to avoid triggering userVotes collection lazy load
     */
    public VoteResponse toResponse(Vote vote, List<VoteOption> options,
                                   Map<UUID, Long> optionCounts, long totalVoteCount) {
        VoteResponse response = new VoteResponse();
        response.setId(vote.getId().toString());
        response.setTitle(vote.getTitle());
        response.setDescription(vote.getDescription());
        response.setCreatorId(vote.getCreator().getId().toString());
        response.setCreatorUsername(vote.getCreator().getUsername());
        response.setStartTime(vote.getStartTime());
        response.setEndTime(vote.getEndTime());
        response.setActive(vote.isActive());
        response.setAllowMultipleChoices(vote.isAllowMultipleChoices());
        response.setAnonymous(vote.isAnonymous());
        response.setCreatedAt(vote.getCreatedAt());
        response.setUpdatedAt(vote.getUpdatedAt());
        response.setTotalVotes(totalVoteCount);
        response.setVotingActive(vote.isVotingActive());
        response.setOptions(options.stream()
                .map(opt -> toOptionResponse(opt, optionCounts.getOrDefault(opt.getId(), 0L)))
                .toList());
        List<TagDto> tagDtos = (vote.getTags() != null)
                ? vote.getTags().stream()
                        .map(tag -> new TagDto(
                                tag.getId().toString(),
                                tag.getName(),
                                tag.getSlug(),
                                tag.getDescription(),
                                tag.getDisplayOrder(),
                                tag.getUsageCount()))
                        .toList()
                : List.of();
        response.setTags(tagDtos);
        return response;
    }

    public VoteResponse.VoteOptionResponse toOptionResponse(VoteOption option, long voteCount) {
        VoteResponse.VoteOptionResponse resp = new VoteResponse.VoteOptionResponse();
        resp.setId(option.getId().toString());
        resp.setText(option.getText());
        resp.setDescription(option.getDescription());
        resp.setDisplayOrder(option.getDisplayOrder());
        resp.setCreatedAt(option.getCreatedAt());
        resp.setVotes(voteCount);
        return resp;
    }

    /**
     * @param optionCounts Pre-fetched Map<optionId, voteCount>
     * @param totalVoteCount Pre-fetched total vote count
     */
    public VoteResultResponse toResultResponse(Vote vote, List<VoteOption> options,
                                               int totalParticipants,
                                               Map<UUID, Long> optionCounts,
                                               long totalVoteCount) {
        VoteResultResponse response = new VoteResultResponse();
        response.setId(vote.getId().toString());
        response.setTitle(vote.getTitle());
        response.setDescription(vote.getDescription());
        response.setCreatorId(vote.getCreator().getId().toString());
        response.setCreatorUsername(vote.getCreator().getUsername());
        response.setStartTime(vote.getStartTime());
        response.setEndTime(vote.getEndTime());
        response.setActive(vote.isActive());
        response.setAllowMultipleChoices(vote.isAllowMultipleChoices());
        response.setAnonymous(vote.isAnonymous());
        response.setCreatedAt(vote.getCreatedAt());
        response.setTotalVotes(totalVoteCount);
        response.setVotingActive(vote.isVotingActive());
        response.setTotalParticipants(totalParticipants);

        List<VoteResultResponse.VoteOptionResultResponse> optionResults = options.stream()
            .map(opt -> toOptionResultResponse(opt, totalVoteCount, vote.isAnonymous(),
                    optionCounts.getOrDefault(opt.getId(), 0L)))
            .toList();
        response.setOptions(optionResults);
        return response;
    }

    public VoteResultResponse.VoteOptionResultResponse toOptionResultResponse(
            VoteOption option, long totalVotes, boolean isAnonymous, long voteCount) {
        VoteResultResponse.VoteOptionResultResponse result = new VoteResultResponse.VoteOptionResultResponse();
        result.setId(option.getId().toString());
        result.setText(option.getText());
        result.setDescription(option.getDescription());
        result.setDisplayOrder(option.getDisplayOrder());
        result.setVoteCount(voteCount);

        double percentage = totalVotes > 0 ? (double) voteCount / totalVotes * 100 : 0.0;
        result.setPercentage(percentage);

        if (!isAnonymous) {
            List<VoteResultResponse.VoterResponse> voters = option.getUserVotes().stream()
                .map(userVote -> {
                    VoteResultResponse.VoterResponse voter = new VoteResultResponse.VoterResponse();
                    voter.setUserId(userVote.getUser().getId().toString());
                    voter.setUsername(userVote.getUser().getUsername());
                    voter.setVotedAt(userVote.getCreatedAt());
                    return voter;
                })
                .toList();
            result.setVoters(voters);
        } else {
            result.setVoters(new ArrayList<>());
        }
        return result;
    }
}

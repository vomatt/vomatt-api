package com.vomatt.votes;

import com.vomatt.tags.dto.TagDto;
import com.vomatt.votes.dto.VoteResponse;
import com.vomatt.votes.dto.VoteResultResponse;
import com.vomatt.votes.dto.VoterResponse;
import com.vomatt.entity.UserVote;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VoteMapper {

    /**
     * The single sealing point: while a Poll has not Ended, per-option counts stay null so no endpoint
     * can leak them. Turnout ({@code participantCount}) is always present. Counts come from the stored
     * option counts, so no Ballot rows are loaded.
     */
    public VoteResponse toResponse(Vote vote, List<VoteOption> options) {
        boolean sealed = vote.isResultsSealed();
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
        response.setVoterVisibility(vote.getVoterVisibility());
        response.setCreatedAt(vote.getCreatedAt());
        response.setUpdatedAt(vote.getUpdatedAt());
        long turnout = turnout(options);
        response.setTotalVotes(turnout);
        response.setParticipantCount(turnout);
        response.setVotingActive(vote.isVotingActive());
        response.setOptions(options.stream()
                .map(opt -> toOptionResponse(opt, sealed))
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

    public VoteResponse.VoteOptionResponse toOptionResponse(VoteOption option, boolean sealed) {
        VoteResponse.VoteOptionResponse resp = new VoteResponse.VoteOptionResponse();
        resp.setId(option.getId().toString());
        resp.setText(option.getText());
        resp.setDescription(option.getDescription());
        resp.setDisplayOrder(option.getDisplayOrder());
        resp.setCreatedAt(option.getCreatedAt());
        resp.setVotes(sealed ? null : (long) option.getVoteCount());
        return resp;
    }

    /** Results of an Ended Poll; Support uses the number of Participants as denominator. */
    public VoteResultResponse toResultResponse(Vote vote, List<VoteOption> options) {
        long participants = turnout(options);
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
        response.setTotalVotes(participants);
        response.setVotingActive(vote.isVotingActive());
        response.setTotalParticipants(participants);
        response.setOptions(options.stream()
                .map(opt -> toOptionResultResponse(opt, participants))
                .toList());
        return response;
    }

    public VoteResultResponse.VoteOptionResultResponse toOptionResultResponse(VoteOption option, long participants) {
        VoteResultResponse.VoteOptionResultResponse result = new VoteResultResponse.VoteOptionResultResponse();
        result.setId(option.getId().toString());
        result.setText(option.getText());
        result.setDescription(option.getDescription());
        result.setDisplayOrder(option.getDisplayOrder());
        result.setVoteCount(option.getVoteCount());
        result.setPercentage(participants > 0 ? (double) option.getVoteCount() / participants * 100 : 0.0);
        return result;
    }

    public VoterResponse toVoterResponse(UserVote ballot) {
        return new VoterResponse(ballot.getUser().getId().toString(), ballot.getUser().getUsername(),
                ballot.getOption().getId().toString(), ballot.getCreatedAt());
    }

    // Turnout: Polls are single-choice, so the option counts add up to the number of Participants
    private static long turnout(List<VoteOption> options) {
        return options.stream().mapToLong(VoteOption::getVoteCount).sum();
    }
}

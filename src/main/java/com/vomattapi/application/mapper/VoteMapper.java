package com.vomattapi.application.mapper;

import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.dto.response.VoteResultResponse;
import com.vomattapi.domain.vote.Vote;
import com.vomattapi.domain.vote.VoteOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class VoteMapper {

    public VoteResponse toResponse(Vote vote, List<VoteOption> options) {
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
        response.setTotalVotes(vote.getTotalVotes());
        response.setVotingActive(vote.isVotingActive());
        response.setOptions(options.stream().map(this::toOptionResponse).collect(Collectors.toList()));
        return response;
    }

    public VoteResponse.VoteOptionResponse toOptionResponse(VoteOption option) {
        VoteResponse.VoteOptionResponse resp = new VoteResponse.VoteOptionResponse();
        resp.setId(option.getId().toString());
        resp.setText(option.getText());
        resp.setDescription(option.getDescription());
        resp.setDisplayOrder(option.getDisplayOrder());
        resp.setCreatedAt(option.getCreatedAt());
        resp.setVotes(option.getVoteCount());
        return resp;
    }

    public VoteResultResponse toResultResponse(Vote vote, List<VoteOption> options, int totalParticipants) {
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
        response.setTotalVotes(vote.getTotalVotes());
        response.setVotingActive(vote.isVotingActive());
        response.setTotalParticipants(totalParticipants);

        List<VoteResultResponse.VoteOptionResultResponse> optionResults = options.stream()
            .map(option -> toOptionResultResponse(option, vote.getTotalVotes(), vote.isAnonymous()))
            .collect(Collectors.toList());
        response.setOptions(optionResults);
        return response;
    }

    public VoteResultResponse.VoteOptionResultResponse toOptionResultResponse(
            VoteOption option, long totalVotes, boolean isAnonymous) {
        VoteResultResponse.VoteOptionResultResponse result = new VoteResultResponse.VoteOptionResultResponse();
        result.setId(option.getId().toString());
        result.setText(option.getText());
        result.setDescription(option.getDescription());
        result.setDisplayOrder(option.getDisplayOrder());
        result.setVoteCount(option.getVoteCount());

        double percentage = totalVotes > 0 ? (double) option.getVoteCount() / totalVotes * 100 : 0.0;
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
                .collect(Collectors.toList());
            result.setVoters(voters);
        } else {
            result.setVoters(new ArrayList<>());
        }
        return result;
    }
}

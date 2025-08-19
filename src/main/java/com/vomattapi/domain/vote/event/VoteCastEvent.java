package com.vomattapi.domain.vote.event;

import java.util.List;

import lombok.Getter;

@Getter
public class VoteCastEvent extends VoteEvent {
    
    private final List<String> optionIds;
    private final String ipAddress;
    
    public VoteCastEvent(String voteId, String memberId, List<String> optionIds, String ipAddress) {
        super(voteId, memberId);
        this.optionIds = optionIds;
        this.ipAddress = ipAddress;
    }
}
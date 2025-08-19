package com.vomattapi.domain.vote.event;

import lombok.Getter;

@Getter
public class VoteDeactivatedEvent extends VoteEvent {
    
    private final String reason;
    
    public VoteDeactivatedEvent(String voteId, String memberId, String reason) {
        super(voteId, memberId);
        this.reason = reason;
    }
}
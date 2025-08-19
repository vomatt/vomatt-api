package com.vomattapi.domain.vote.event;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public abstract class VoteEvent {
    
    protected final String voteId;
    protected final String memberId;
    protected final LocalDateTime timestamp;
    
    public VoteEvent(String voteId, String memberId) {
        this.voteId = voteId;
        this.memberId = memberId;
        this.timestamp = LocalDateTime.now();
    }
}
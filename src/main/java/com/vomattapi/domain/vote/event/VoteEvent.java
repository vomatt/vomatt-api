package com.vomattapi.domain.vote.event;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public abstract class VoteEvent {
    
    protected final String voteId;
    protected final String userId;
    protected final LocalDateTime timestamp;
    
    public VoteEvent(String voteId, String userId) {
        this.voteId = voteId;
        this.userId = userId;
        this.timestamp = LocalDateTime.now();
    }
}
package com.vomattapi.domain.vote.event;

import lombok.Getter;

@Getter
public class VoteCreatedEvent extends VoteEvent {
    
    private final String title;
    private final String description;
    private final int optionsCount;
    
    public VoteCreatedEvent(String voteId, String memberId, String title, String description, int optionsCount) {
        super(voteId, memberId);
        this.title = title;
        this.description = description;
        this.optionsCount = optionsCount;
    }
}
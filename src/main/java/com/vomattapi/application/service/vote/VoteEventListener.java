package com.vomattapi.application.service.vote;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.vomattapi.domain.vote.event.VoteCastEvent;
import com.vomattapi.domain.vote.event.VoteCreatedEvent;
import com.vomattapi.domain.vote.event.VoteDeactivatedEvent;
import com.vomattapi.infrastructure.config.ApplicationConfigurationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class VoteEventListener {

    private final ApplicationConfigurationProperties appConfig;

    @EventListener
    @Async
    public void handleVoteCreated(VoteCreatedEvent event) {
        log.info("Vote created event: voteId={}, creatorId={}, title={}, optionsCount={}", 
                event.getVoteId(), event.getUserId(), event.getTitle(), event.getOptionsCount());
        
        // Here you can add additional logic such as:
        // - Send notifications to subscribers
        // - Update analytics/statistics
        // - Log for audit purposes
        // - Send email notifications
    }

    @EventListener
    @Async
    public void handleVoteCast(VoteCastEvent event) {
        log.info("Vote cast event: voteId={}, voterId={}, optionsSelected={}, from={}", 
                event.getVoteId(), event.getUserId(), event.getOptionIds().size(), event.getIpAddress());
        
        // Additional logic:
        // - Update real-time vote counters
        // - Send notifications to vote creator
        // - Update member activity records
        // - Check for suspicious voting patterns
    }

    @EventListener
    @Async
    public void handleVoteDeactivated(VoteDeactivatedEvent event) {
        log.info("Vote deactivated event: voteId={}, deactivatedBy={}, reason={}", 
                event.getVoteId(), event.getUserId(), event.getReason());
        
        // Additional logic:
        // - Send final results to participants
        // - Archive vote data
        // - Update statistics
        // - Send notifications
    }
}
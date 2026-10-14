package com.vomatt.votes;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@ConfigurationProperties(prefix = "app.vote")
@Component
@Data
public class VoteConfigurationProperties {
    
    private int maxOptionsPerVote = 10;
    private int minOptionsPerVote = 2;
    private Duration defaultVoteDuration = Duration.ofDays(7);
    private Duration maxVoteDuration = Duration.ofDays(365);
    /** How long after posting an Open Poll can still be Deleted (ADR 0004). */
    private Duration deleteWindow = Duration.ofMinutes(20);
    private int maxTitleLength = 200;
    private int maxDescriptionLength = 1000;
    private int maxOptionTextLength = 200;
    private int maxOptionDescriptionLength = 500;
    private boolean allowAnonymousVoting = true;
    private boolean allowMultipleChoicesByDefault = false;
    private BallotRateLimit ballotRateLimit = new BallotRateLimit();

    /** Per-user fixed-window cap on Ballot writes (cast, Retract, remove Selection). */
    @Data
    public static class BallotRateLimit {
        private int capacity = 30;
        private Duration window = Duration.ofSeconds(60);
    }
}
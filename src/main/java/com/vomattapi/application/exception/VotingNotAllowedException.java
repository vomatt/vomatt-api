package com.vomattapi.application.exception;

public class VotingNotAllowedException extends BusinessRuleViolationException {
    
    public VotingNotAllowedException(String reason) {
        super("Voting not allowed: " + reason);
    }
    
    public VotingNotAllowedException(String voteId, String reason) {
        super(String.format("Voting not allowed for vote %s: %s", voteId, reason));
    }
}
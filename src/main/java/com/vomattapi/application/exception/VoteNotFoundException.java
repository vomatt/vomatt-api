package com.vomattapi.application.exception;

public class VoteNotFoundException extends EntityNotFoundException {
    
    public VoteNotFoundException(String voteId) {
        super("Vote", voteId);
    }
    
    public VoteNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
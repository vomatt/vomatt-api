package com.vomatt.repository;

import java.util.UUID;

/** Projection: the option a user's Ballot holds in a Poll, and whether it is Anonymous. */
public interface BallotSelection {
    UUID getVoteId();
    UUID getOptionId();
    boolean isAnonymous();
}

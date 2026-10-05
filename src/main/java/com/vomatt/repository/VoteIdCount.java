package com.vomatt.repository;

import java.util.UUID;

/** Projection: a count per Poll, for per-page batch loading. */
public interface VoteIdCount {
    UUID getVoteId();
    Long getCount();
}

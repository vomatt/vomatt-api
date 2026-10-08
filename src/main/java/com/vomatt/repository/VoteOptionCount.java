package com.vomatt.repository;

import java.util.UUID;

/**
 * Projection 介面：多個投票的各選項票數，供列表一次查詢、避免 N+1
 */
public interface VoteOptionCount {
    UUID getVoteId();
    UUID getOptionId();
    Long getCount();
}

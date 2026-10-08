package com.vomatt.repository;

import java.util.UUID;

/**
 * Projection 介面：依投票分組的計數（參與人數、留言數），供列表一次查詢、避免 N+1
 */
public interface VoteCount {
    UUID getVoteId();
    Long getCount();
}

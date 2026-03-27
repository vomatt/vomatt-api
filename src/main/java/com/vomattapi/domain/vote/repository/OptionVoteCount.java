package com.vomattapi.domain.vote.repository;

import java.util.UUID;

/**
 * Projection 介面：查詢各選項的投票計數，避免 Object[] 不安全轉型
 */
public interface OptionVoteCount {
    UUID getOptionId();
    Long getCount();
}

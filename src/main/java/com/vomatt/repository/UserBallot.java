package com.vomatt.repository;

import java.util.UUID;

/**
 * Projection 介面：某使用者在各投票所選的選項
 */
public interface UserBallot {
    UUID getVoteId();
    UUID getOptionId();
}

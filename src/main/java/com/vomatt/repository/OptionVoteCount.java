package com.vomatt.repository;

import java.util.UUID;

/**
 * Projection interface: per-option vote counts, avoiding unsafe Object[] casts
 */
public interface OptionVoteCount {
    UUID getOptionId();
    Long getCount();
}

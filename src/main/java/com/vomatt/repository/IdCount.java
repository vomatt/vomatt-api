package com.vomatt.repository;

import java.util.UUID;

/** Projection: a count per id (Poll, Comment, …), for per-page batch loading. */
public interface IdCount {
    UUID getId();
    Long getCount();
}

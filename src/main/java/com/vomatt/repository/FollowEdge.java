package com.vomatt.repository;

import java.util.UUID;

/** One follow, as the pair of user ids: {@code followerId} follows {@code followedId}. */
public record FollowEdge(UUID followerId, UUID followedId) {}

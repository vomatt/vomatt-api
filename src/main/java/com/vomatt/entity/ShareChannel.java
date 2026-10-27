package com.vomatt.entity;

/** Where a user shared a Poll's link; stored by name in {@code vote_shares.channel}. NATIVE is the system share sheet. */
public enum ShareChannel {
    COPY_LINK, NATIVE, LINE, FACEBOOK, X, THREADS, OTHER
}

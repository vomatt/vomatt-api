package com.vomatt.entity;

/** Who can reach a Poll. Changeable only while the Poll is Scheduled. */
public enum Audience {
    PUBLIC,      // listed in Feed / Explore / Search and the owner's profile (default)
    UNLISTED,    // reachable only through its link
    INVITE_ONLY  // reserved: only the owner and its Invitees
}

package com.vomatt.entity;

/** Who may see which option each Participant chose, once the Poll has Ended. Fixed once the Poll is Open. */
public enum VoterVisibility {
    NOBODY,     // nobody, including the owner
    OWNER,      // the owner only (default)
    SIGNED_IN   // any signed-in user
}

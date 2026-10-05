package com.vomatt.entity;

/** Poll lifecycle state, derived from start and end time (see CONTEXT.md). */
public enum VoteStatus {
    SCHEDULED,  // start time not reached: visible, no Ballots
    OPEN,       // accepting Ballots, changes and Retractions
    ENDED       // end time passed or Closed; takes precedence over SCHEDULED
}

package com.vomatt.repository;

import java.time.Instant;
import java.util.UUID;

/** Projection: one Invitee of a Poll (a user, an email, or an email tied to a user) and whether they hold a Ballot in it. */
public interface InviteeStatus {
    UUID getUserId();
    String getUsername();
    /** The user's own names, before their visibility settings mask them */
    String getDisplayName();
    String getFirstName();
    String getLastName();
    String getAvatarUrl();
    String getEmail();
    Instant getInvitedAt();
    boolean getHasBallot();
}

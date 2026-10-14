package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Projection: one Invitee of a Poll (a user, an email, or an email tied to a user) and whether they hold a Ballot in it. */
public interface InviteeStatus {
    UUID getUserId();
    String getUsername();
    String getEmail();
    OffsetDateTime getInvitedAt();
    boolean getHasBallot();
}

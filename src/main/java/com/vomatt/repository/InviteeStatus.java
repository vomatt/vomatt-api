package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Projection: one Invitee of a Poll (a user, or an email not yet tied to one) and whether they hold a Ballot in it. */
public interface InviteeStatus {
    UUID getUserId();
    String getUsername();
    String getEmail();
    OffsetDateTime getInvitedAt();
    boolean getHasBallot();
}

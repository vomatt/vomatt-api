package com.vomatt.entity;

/**
 * What a notification is about. Notifications are derived on read (ADR 0003, ADR 0005); the kind and the id of the row
 * it is derived from identify one, and its read state is stored under that pair.
 */
public enum NotificationKind {
    /** A Poll the user owns, holds a Ballot in or is invited to has Ended. Source: the Poll. */
    RESULT,
    /** An Invite-only Poll the user is invited to was Cancelled before it opened (legacy, ADR 0004). Source: the Poll. */
    CANCELLED,
    /** The user is an Invitee of a Scheduled or Open Invite-only Poll. Source: the Invitee row. */
    INVITATION,
    /** Someone followed the user. Source: the follow row. */
    FOLLOWED,
    /** Someone commented on the user's Poll or replied to the user's Comment. Source: the Comment or Reply. */
    COMMENT,
    /** A Poll the user can cast a Ballot in and has not ends within a day. Source: the Poll. */
    CLOSING_SOON
}

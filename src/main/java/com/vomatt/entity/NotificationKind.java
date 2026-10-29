package com.vomatt.entity;

/**
 * What a notification is about. Notifications are derived on read (ADR 0003, ADR 0005); the kind and the id of the row
 * it is derived from identify one, and its read state is stored under that pair.
 */
public enum NotificationKind {
    /** A Poll the user owns, holds a Ballot in or is invited to has Ended. Source: the Poll. */
    RESULT(NotificationCategory.POLL_END),
    /** An Invite-only Poll the user is invited to was Cancelled before it opened (legacy, ADR 0004). Source: the Poll. */
    CANCELLED(NotificationCategory.POLL_END),
    /** The user is an Invitee of a Scheduled or Open Invite-only Poll. Source: the Invitee row. */
    INVITATION(NotificationCategory.INVITATION),
    /** Someone followed the user. Source: the follow row. */
    FOLLOWED(NotificationCategory.FOLLOW),
    /** Someone commented on the user's Poll or replied to the user's Comment. Source: the Comment or Reply. */
    COMMENT(NotificationCategory.CONVERSATION),
    /** A Poll the user can cast a Ballot in and has not ends within a day. Source: the Poll. */
    CLOSING_SOON(NotificationCategory.POLL_END),
    /** Someone liked the user's Comment or Reply. Source: the like row. */
    LIKE(NotificationCategory.LIKE);

    private final NotificationCategory category;

    NotificationKind(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory category() {
        return category;
    }
}

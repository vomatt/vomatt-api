package com.vomatt.entity;

/** The tab a notification is filed under; each {@link NotificationKind} belongs to exactly one. */
public enum NotificationCategory {
    /** Comments and Replies: COMMENT. */
    CONVERSATION,
    /** Likes on the user's Comments: LIKE. */
    LIKE,
    /** A Poll Ended, was Cancelled or ends soon: RESULT, CANCELLED, CLOSING_SOON. */
    POLL_END,
    /** Invitations to Invite-only Polls: INVITATION. */
    INVITATION,
    /** New followers: FOLLOWED. */
    FOLLOW
}

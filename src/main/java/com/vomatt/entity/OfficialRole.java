package com.vomatt.entity;

/** A member's role in an official account; what each may do while acting as it (see {@code ActAsFilter}). */
public enum OfficialRole {
    OWNER, ADMIN, CHIEF_EDITOR, EDITOR, ANALYST;

    /** Add, remove and change the roles of members (an ADMIN never touches an OWNER nor grants OWNER). */
    public boolean canManageMembers() {
        return this == OWNER || this == ADMIN;
    }

    /** Edit the official account's profile: display name, avatar, visibility. */
    public boolean canEditProfile() {
        return this == OWNER || this == ADMIN;
    }

    /** Any write as the official account: create Polls, comment, like (ANALYST is read-only). */
    public boolean canPublish() {
        return this != ANALYST;
    }

    /** Edit or delete any of the official account's Polls and Comments; an EDITOR only those it published itself. */
    public boolean canEditAllContent() {
        return this == OWNER || this == ADMIN || this == CHIEF_EDITOR;
    }

    /** Read the official account's result reports. */
    public boolean canReadReports() {
        return this == OWNER || this == ADMIN || this == ANALYST;
    }
}

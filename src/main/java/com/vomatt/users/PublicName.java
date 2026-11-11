package com.vomatt.users;

import java.util.Set;

/** The names other people may see of a user: each null when the owner hides it or never set it. */
public record PublicName(String displayName, String firstName, String lastName) {

    public static final PublicName NONE = new PublicName(null, null, null);

    /** These names with every field not in {@code shown} hidden. */
    PublicName showing(Set<VisibilityField> shown) {
        return new PublicName(
                shown.contains(VisibilityField.DISPLAY_NAME) ? displayName : null,
                shown.contains(VisibilityField.FIRST_NAME) ? firstName : null,
                shown.contains(VisibilityField.LAST_NAME) ? lastName : null);
    }
}

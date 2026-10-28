package com.vomatt.common.util;

import java.util.UUID;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.repository.UserRepository;

/** Checks for writes guarded by the signed-in user's row. */
public final class UserGuard {

    private UserGuard() {}

    /**
     * For a write that inserted nothing although the signed-in user's row guarded it: an access token outlives account
     * deletion, so the user may be gone since the request passed the filter.
     */
    public static void requireExists(UserRepository users, UUID userId) {
        if (!users.existsById(userId)) {
            throw ApiException.notFound(MessageKey.USER_NOT_FOUND);
        }
    }
}

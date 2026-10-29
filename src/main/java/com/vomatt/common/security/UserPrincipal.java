package com.vomatt.common.security;

import com.vomatt.common.constant.UserRole;

import java.util.List;

/**
 * What the access token says about the caller: the id, the roles it grants and its session (see {@link JwtUtil});
 * {@code sessionId} is null on tokens issued before it was written.
 */
public record UserPrincipal(String userId, List<String> roles, String sessionId) {

    /** Whether this token grants the admin role; only admin-scope tokens keep it (see {@link JwtUtil#parseToken}). */
    public boolean grantsAdmin() {
        return roles != null && roles.stream().anyMatch(UserRole::isAdmin);
    }
}

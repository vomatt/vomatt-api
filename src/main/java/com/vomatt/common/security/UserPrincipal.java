package com.vomatt.common.security;

import com.vomatt.common.constant.UserRole;

import java.util.List;

/** What the access token says about the caller: only the id and the roles it grants (see {@link JwtUtil}). */
public record UserPrincipal(String userId, List<String> roles) {

    /** Whether this token grants the admin role; only admin-scope tokens keep it (see {@link JwtUtil#parseToken}). */
    public boolean grantsAdmin() {
        return roles != null && roles.stream().anyMatch(UserRole::isAdmin);
    }
}

package com.vomatt.common.security;

import java.util.List;

public record UserPrincipal(String userId, String email, List<String> roles) {

    /** The user id, or null for a guest on a public endpoint. */
    public static String idOrNull(UserPrincipal principal) {
        return principal != null ? principal.userId() : null;
    }
}

package com.vomatt.common.security;

import java.util.List;

public record UserPrincipal(String userId, String email, List<String> roles) {
}

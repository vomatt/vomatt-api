package com.vomatt.common.security;

import com.vomatt.entity.OfficialRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The real member behind a request sent with {@code X-Act-As}: the principal is then the official account (so Poll,
 * comment and notification code keeps one user id), and this holds who acts and with which role. Set by
 * {@link ActAsFilter} as the authentication's details.
 */
public record ActingMember(UUID memberId, UUID officialId, OfficialRole role) {

    /** The member acting on this request, or empty when the caller acts as themself. */
    public static Optional<ActingMember> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof ActingMember acting ? Optional.of(acting) : Optional.empty();
    }

    /** The real person behind the principal: the acting member, or the principal itself. */
    public static String realUserId(UserPrincipal principal) {
        return current().map(a -> a.memberId().toString()).orElse(principal.userId());
    }
}

package com.vomatt.common.security;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommunityRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The real member behind a request sent with {@code X-Act-As}: the principal is then the community account (so Poll,
 * comment and notification code keeps one user id), and this holds who acts and with which role. Set by
 * {@link ActAsFilter} as the authentication's details.
 */
public record ActingMember(UUID memberId, UUID communityId, CommunityRole role) {

    /** The member acting on this request, or empty when the caller acts as themself. */
    public static Optional<ActingMember> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof ActingMember acting ? Optional.of(acting) : Optional.empty();
    }

    /** The real person behind the principal: the acting member, or the principal itself. */
    public static String realUserId(UserPrincipal principal) {
        return current().map(a -> a.memberId().toString()).orElse(principal.userId());
    }

    /**
     * While acting, a role that cannot edit all of the community account's content (EDITOR) may edit or delete only
     * the Polls and Comments this member published ({@code acted_by}); 403 {@code community.role_forbidden} otherwise.
     */
    public static void requireMayEdit(UUID actedBy) {
        current().filter(a -> !a.role().canEditAllContent() && !a.memberId().equals(actedBy))
                .ifPresent(a -> {
                    throw ApiException.forbidden(MessageKey.COMMUNITY_ROLE_FORBIDDEN);
                });
    }
}

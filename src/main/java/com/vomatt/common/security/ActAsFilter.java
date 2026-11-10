package com.vomatt.common.security;

import com.vomatt.common.constant.UserRole;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.CommunityRole;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;

/**
 * Acting as a community account: a signed-in member sends {@code X-Act-As: <communityUserId>}. Runs after
 * {@link JwtAuthFilter}; a non-member gets 403 {@code community.not_member}. On success the principal becomes the
 * community account and {@link ActingMember} keeps the real member and role. Role rules by route (in
 * {@link SecurityEndpoints}): account and session routes are never reachable this way, the profile routes need
 * OWNER or ADMIN, member management, marking notifications read and following (POST only) take any role, and any other write needs a
 * role that can publish (ANALYST is read-only); the rest is checked by the services. Without a signed-in caller, and on sign-in, refresh and logout, the header is ignored.
 * <p>
 * A write to a locked community account gets 403 {@code community.locked}, checked here for every route at once: the
 * account in a {@code /api/communities/{id}} path, else the one acted as. Deleting it, transferring ownership, leaving,
 * unlocking and choosing which to keep stay open. The check runs before the request's transaction, so a write already
 * past it when the account gets locked still lands.
 */
@Component
@RequiredArgsConstructor
public class ActAsFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Act-As";

    private static final RequestMatcher DENIED = new OrRequestMatcher(JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_DENIED),
            on(DELETE, SecurityEndpoints.ACT_AS_DENIED_DELETE));
    private static final RequestMatcher PUBLIC_AUTH = JwtAuthFilter.anyOf(SecurityEndpoints.PUBLIC_AUTH);
    private static final RequestMatcher PROFILE = JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_PROFILE);
    private static final RequestMatcher ANY_ROLE = new OrRequestMatcher(JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_ANY_ROLE),
            on(POST, SecurityEndpoints.ACT_AS_ANY_ROLE_POST));
    private static final RequestMatcher LOCKED_ALLOWED = new OrRequestMatcher(JwtAuthFilter.anyOf(SecurityEndpoints.LOCKED_COMMUNITY_ALLOWED),
            on(DELETE, SecurityEndpoints.LOCKED_COMMUNITY_ALLOWED_DELETE));
    private static final PathPatternRequestMatcher COMMUNITY_PATH = PathPatternRequestMatcher.withDefaults().matcher("/api/communities/{communityId}/**");
    private static final PathPatternRequestMatcher MEMBER_PATH = PathPatternRequestMatcher.withDefaults()
            .matcher(DELETE, "/api/communities/{communityId}/members/{userId}");

    private final CommunityMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final SecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal member) || PUBLIC_AUTH.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID memberId = UUID.fromString(member.userId());
        UUID communityId = null;
        CommunityRole role = null;
        if (StringUtils.hasText(header)) {
            communityId = parseUuid(header.trim());
            Optional<CommunityRole> found = communityId == null ? Optional.empty() : memberRepository.findActiveRole(communityId, memberId);
            if (found.isEmpty()) {
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.COMMUNITY_NOT_MEMBER);
                return;
            }
            role = found.get();
            if (!allowed(role, request)) {
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.COMMUNITY_ROLE_FORBIDDEN);
                return;
            }
        }
        if (writesToLocked(request, memberId, communityId)) {
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.COMMUNITY_LOCKED);
            return;
        }
        if (role != null) {
            // Same session: revoking the member's session cuts their acting too
            UserPrincipal community = new UserPrincipal(communityId.toString(), List.of(UserRole.COMMUNITY), member.sessionId());
            var acting = new UsernamePasswordAuthenticationToken(community, null, List.of(new SimpleGrantedAuthority("ROLE_COMMUNITY")));
            acting.setDetails(new ActingMember(memberId, communityId, role));
            SecurityContextHolder.getContext().setAuthentication(acting);
        }
        filterChain.doFilter(request, response);
    }

    private boolean writesToLocked(HttpServletRequest request, UUID memberId, UUID actingAs) {
        if (JwtAuthFilter.READ_METHODS.contains(request.getMethod()) || LOCKED_ALLOWED.matches(request)
                || memberId.equals(parseUuid(MEMBER_PATH.matcher(request).getVariables().get("userId")))) {
            return false;
        }
        String pathId = COMMUNITY_PATH.matcher(request).getVariables().get("communityId");
        UUID target = pathId != null ? parseUuid(pathId) : actingAs;
        return target != null && userRepository.existsByIdAndLockedAtIsNotNull(target);
    }

    private static boolean allowed(CommunityRole role, HttpServletRequest request) {
        if (DENIED.matches(request)) {
            return false;
        }
        if (PROFILE.matches(request)) {
            return JwtAuthFilter.READ_METHODS.contains(request.getMethod()) || role.canEditProfile();
        }
        return JwtAuthFilter.READ_METHODS.contains(request.getMethod()) || ANY_ROLE.matches(request) || role.canPublish();
    }

    private static RequestMatcher on(HttpMethod method, String[] patterns) {
        return new OrRequestMatcher(Arrays.stream(patterns)
                .map(p -> PathPatternRequestMatcher.withDefaults().matcher(method, p)).map(RequestMatcher.class::cast).toList());
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

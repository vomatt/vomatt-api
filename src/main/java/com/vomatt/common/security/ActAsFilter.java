package com.vomatt.common.security;

import com.vomatt.common.constant.UserRole;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.OfficialRole;
import com.vomatt.repository.OfficialMemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

/**
 * Acting as an official account: a signed-in member sends {@code X-Act-As: <officialUserId>}. Runs after
 * {@link JwtAuthFilter}; a non-member gets 403 {@code official.not_member}. On success the principal becomes the
 * official account and {@link ActingMember} keeps the real member and role. Role rules by route (in
 * {@link SecurityEndpoints}): account and session routes are never reachable this way, the profile routes need
 * OWNER or ADMIN, member management and marking notifications read take any role, and any other write needs a
 * role that can publish (ANALYST is read-only); the rest is checked by the services. Without a signed-in caller, and on sign-in, refresh and logout, the header is ignored.
 */
@Component
@RequiredArgsConstructor
public class ActAsFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Act-As";

    private static final RequestMatcher DENIED = new OrRequestMatcher(JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_DENIED),
            new OrRequestMatcher(Arrays.stream(SecurityEndpoints.ACT_AS_DENIED_DELETE)
                    .map(p -> PathPatternRequestMatcher.withDefaults().matcher(DELETE, p)).map(RequestMatcher.class::cast).toList()));
    private static final RequestMatcher PUBLIC_AUTH = JwtAuthFilter.anyOf(SecurityEndpoints.PUBLIC_AUTH);
    private static final RequestMatcher PROFILE = JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_PROFILE);
    private static final RequestMatcher ANY_ROLE = JwtAuthFilter.anyOf(SecurityEndpoints.ACT_AS_ANY_ROLE);

    private final OfficialMemberRepository memberRepository;
    private final SecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!StringUtils.hasText(header) || auth == null || !(auth.getPrincipal() instanceof UserPrincipal member)
                || PUBLIC_AUTH.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID officialId = parseUuid(header.trim());
        Optional<OfficialRole> role = officialId == null ? Optional.empty()
                : memberRepository.findActiveRole(officialId, UUID.fromString(member.userId()));
        if (role.isEmpty()) {
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.OFFICIAL_NOT_MEMBER);
            return;
        }
        if (!allowed(role.get(), request)) {
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, MessageKey.OFFICIAL_ROLE_FORBIDDEN);
            return;
        }
        // Same session: revoking the member's session cuts their acting too
        UserPrincipal official = new UserPrincipal(officialId.toString(), List.of(UserRole.OFFICIAL), member.sessionId());
        var acting = new UsernamePasswordAuthenticationToken(official, null, List.of(new SimpleGrantedAuthority("ROLE_OFFICIAL")));
        acting.setDetails(new ActingMember(UUID.fromString(member.userId()), officialId, role.get()));
        SecurityContextHolder.getContext().setAuthentication(acting);
        filterChain.doFilter(request, response);
    }

    private static boolean allowed(OfficialRole role, HttpServletRequest request) {
        if (DENIED.matches(request)) {
            return false;
        }
        if (PROFILE.matches(request)) {
            return JwtAuthFilter.READ_METHODS.contains(request.getMethod()) || role.canEditProfile();
        }
        return JwtAuthFilter.READ_METHODS.contains(request.getMethod()) || ANY_ROLE.matches(request) || role.canPublish();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

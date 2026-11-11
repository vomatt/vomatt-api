package com.vomatt.common.security;

import com.vomatt.entity.CommunityRole;
import com.vomatt.repository.CommunityMemberRepository;
import com.vomatt.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActAsFilterTest {

    private final CommunityMemberRepository repository = mock(CommunityMemberRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ActAsFilter filter = new ActAsFilter(repository, userRepository, SecurityTestSupport.errorWriter());

    private final UUID memberId = UUID.randomUUID();
    private final UUID communityId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(memberId.toString(), List.of("user"), "s1"), null, List.of()));
    }

    /** Runs the filter; returns the authentication the rest of the chain saw, or null when it was not reached. */
    private Authentication run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    private MockHttpServletRequest acting(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(ActAsFilter.HEADER, communityId.toString());
        return request;
    }

    private void member(CommunityRole role) {
        when(repository.findActiveRole(communityId, memberId)).thenReturn(Optional.of(role));
    }

    @Test
    void shouldActAsCommunityWhenCallerIsMember() throws Exception {
        signIn();
        member(CommunityRole.EDITOR);

        Authentication auth = run(acting("POST", "/api/votes"), new MockHttpServletResponse());

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(communityId.toString());
        assertThat(principal.sessionId()).isEqualTo("s1");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_COMMUNITY");
        assertThat(auth.getDetails()).isEqualTo(new ActingMember(memberId, communityId, CommunityRole.EDITOR));
    }

    @Test
    void shouldReturn403WhenCallerIsNotMember() throws Exception {
        signIn();
        when(repository.findActiveRole(communityId, memberId)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(acting("GET", "/api/votes/my"), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("community.not_member");
    }

    @Test
    void shouldReturn403WhenHeaderIsNotUuid() throws Exception {
        signIn();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/votes/my");
        request.addHeader(ActAsFilter.HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).isNull();
        assertThat(response.getContentAsString()).contains("community.not_member");
        verify(repository, never()).findActiveRole(any(), any());
    }

    @Test
    void shouldKeepCallerWhenNoHeader() throws Exception {
        signIn();

        Authentication auth = run(new MockHttpServletRequest("POST", "/api/votes"), new MockHttpServletResponse());

        assertThat(((UserPrincipal) auth.getPrincipal()).userId()).isEqualTo(memberId.toString());
        assertThat(ActingMember.current()).isEmpty();
        verify(repository, never()).findActiveRole(any(), any());
    }

    @Test
    void shouldIgnoreHeaderWhenSignedOut() throws Exception {
        assertThat(run(acting("GET", "/api/votes"), new MockHttpServletResponse())).isNull();
        verify(repository, never()).findActiveRole(any(), any());
    }

    @Test
    void shouldIgnoreHeaderWhenLoggingOut() throws Exception {
        signIn();

        Authentication auth = run(acting("POST", "/api/auth/logout"), new MockHttpServletResponse());

        assertThat(((UserPrincipal) auth.getPrincipal()).userId()).isEqualTo(memberId.toString());
        verify(repository, never()).findActiveRole(any(), any());
    }

    @Test
    void shouldAllowOnlyReadsWhenActingAsAnalyst() throws Exception {
        signIn();
        member(CommunityRole.ANALYST);
        assertThat(run(acting("GET", "/api/votes/my"), new MockHttpServletResponse())).isNotNull();

        signIn();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(acting("POST", "/api/votes"), response)).isNull();
        assertThat(response.getContentAsString()).contains("community.role_forbidden");
    }

    @Test
    void shouldAllowMemberRoutesAndMarkingReadWhenActingAsAnalyst() throws Exception {
        member(CommunityRole.ANALYST);
        for (var call : List.of(List.of("DELETE", "/api/communities/" + communityId + "/members/" + memberId),
                List.of("POST", "/api/notifications/comment_x/read"), List.of("POST", "/api/notifications/read-all"))) {
            signIn();
            assertThat(run(acting(call.get(0), call.get(1)), new MockHttpServletResponse())).as(call.toString()).isNotNull();
        }
    }

    @Test
    void shouldLetFollowThroughForTheServiceToRefuseWhenActingAsAnyRole() throws Exception {
        String follow = "/api/users/me/follows/" + UUID.randomUUID();
        for (CommunityRole role : List.of(CommunityRole.ANALYST, CommunityRole.EDITOR)) {
            signIn();
            member(role);
            Authentication auth = run(acting("POST", follow), new MockHttpServletResponse());
            assertThat(auth).as(role.name()).isNotNull();
            assertThat(((UserPrincipal) auth.getPrincipal()).userId()).isEqualTo(communityId.toString());
        }
        signIn();
        member(CommunityRole.ANALYST);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(acting("DELETE", follow), response)).as("ANALYST unfollowing").isNull();
        assertThat(response.getContentAsString()).contains("community.role_forbidden");
    }

    @Test
    void shouldRefuseProfileEditWhenEditor() throws Exception {
        signIn();
        member(CommunityRole.EDITOR);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(acting("PATCH", "/api/users/me"), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(403);

        signIn();
        member(CommunityRole.ADMIN);
        assertThat(run(acting("PUT", "/api/users/me/avatar"), new MockHttpServletResponse())).isNotNull();
    }

    @Test
    void shouldRefuseAccountRoutesWhenActingEvenAsOwner() throws Exception {
        member(CommunityRole.OWNER);
        for (var call : List.of(List.of("POST", "/api/auth/passkey/register/options"), List.of("GET", "/api/users/me/sessions"),
                List.of("DELETE", "/api/users/me/passkeys/x"), List.of("GET", "/api/users/me/export"),
                List.of("POST", "/api/users/me/phone/send-otp"), List.of("POST", "/api/users/me/phone/verify"),
                List.of("DELETE", "/api/users/" + communityId))) {
            signIn();
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(run(acting(call.get(0), call.get(1)), response)).as(call.toString()).isNull();
            assertThat(response.getContentAsString()).contains("community.role_forbidden");
        }
        // Reading a public profile is not account deletion
        signIn();
        assertThat(run(acting("GET", "/api/users/alice"), new MockHttpServletResponse())).isNotNull();
    }

    private void locked() {
        when(userRepository.existsByIdAndLockedAtIsNotNull(communityId)).thenReturn(true);
    }

    private void assertLocked(MockHttpServletRequest request) throws Exception {
        signIn();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(request, response)).as(request.getMethod() + " " + request.getRequestURI()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("community.locked");
    }

    private void assertPasses(MockHttpServletRequest request) throws Exception {
        signIn();
        assertThat(run(request, new MockHttpServletResponse())).as(request.getMethod() + " " + request.getRequestURI()).isNotNull();
    }

    @Test
    void shouldRefuseWritesWhenActingAsLockedCommunity() throws Exception {
        member(CommunityRole.OWNER);
        locked();
        String community = "/api/communities/" + communityId;

        assertLocked(acting("POST", "/api/votes"));
        assertLocked(acting("POST", "/api/votes/" + UUID.randomUUID() + "/comments"));
        assertLocked(acting("PATCH", "/api/users/me"));
        // Member routes and the profile route are refused by their path, with or without the header
        assertLocked(new MockHttpServletRequest("POST", community + "/members"));
        assertLocked(new MockHttpServletRequest("PATCH", community + "/members/" + UUID.randomUUID()));
        assertLocked(new MockHttpServletRequest("DELETE", community + "/members/" + UUID.randomUUID()));
        assertLocked(new MockHttpServletRequest("PATCH", community));
    }

    @Test
    void shouldAllowReadsWhenActingAsLockedCommunity() throws Exception {
        member(CommunityRole.OWNER);
        locked();

        assertPasses(acting("GET", "/api/votes/my"));
        assertPasses(acting("GET", "/api/users/me"));
        verify(userRepository, never()).existsByIdAndLockedAtIsNotNull(any());
    }

    @Test
    void shouldAllowDeleteTransferLeaveUnlockAndKeepWhenCommunityIsLocked() throws Exception {
        member(CommunityRole.OWNER);
        locked();
        String community = "/api/communities/" + communityId;

        assertPasses(new MockHttpServletRequest("DELETE", community));
        assertPasses(acting("DELETE", community));
        assertPasses(new MockHttpServletRequest("POST", community + "/owner"));
        assertPasses(acting("DELETE", community + "/members/" + memberId));
        assertPasses(new MockHttpServletRequest("POST", community + "/unlock"));
        assertPasses(acting("POST", "/api/users/me/communities/keep"));
    }

    @Test
    void shouldAllowWritesWhenCommunityIsNotLocked() throws Exception {
        member(CommunityRole.EDITOR);

        assertPasses(acting("POST", "/api/votes"));
        assertPasses(new MockHttpServletRequest("POST", "/api/communities/" + communityId + "/members"));
    }

    @Test
    void shouldNotCheckLockWhenWriteTargetsNoCommunity() throws Exception {
        assertPasses(new MockHttpServletRequest("POST", "/api/votes/" + UUID.randomUUID() + "/vote"));
        assertPasses(new MockHttpServletRequest("POST", "/api/communities"));

        verify(userRepository, never()).existsByIdAndLockedAtIsNotNull(any());
    }
}

package com.vomatt.common.security;

import com.vomatt.entity.OfficialRole;
import com.vomatt.repository.OfficialMemberRepository;
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

    private final OfficialMemberRepository repository = mock(OfficialMemberRepository.class);
    private final ActAsFilter filter = new ActAsFilter(repository, SecurityTestSupport.errorWriter());

    private final UUID memberId = UUID.randomUUID();
    private final UUID officialId = UUID.randomUUID();

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
        request.addHeader(ActAsFilter.HEADER, officialId.toString());
        return request;
    }

    private void member(OfficialRole role) {
        when(repository.findActiveRole(officialId, memberId)).thenReturn(Optional.of(role));
    }

    @Test
    void shouldActAsOfficialWhenCallerIsMember() throws Exception {
        signIn();
        member(OfficialRole.EDITOR);

        Authentication auth = run(acting("POST", "/api/votes"), new MockHttpServletResponse());

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(officialId.toString());
        assertThat(principal.sessionId()).isEqualTo("s1");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OFFICIAL");
        assertThat(auth.getDetails()).isEqualTo(new ActingMember(memberId, officialId, OfficialRole.EDITOR));
    }

    @Test
    void shouldReturn403WhenCallerIsNotMember() throws Exception {
        signIn();
        when(repository.findActiveRole(officialId, memberId)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(acting("GET", "/api/votes/my"), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("official.not_member");
    }

    @Test
    void shouldReturn403WhenHeaderIsNotUuid() throws Exception {
        signIn();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/votes/my");
        request.addHeader(ActAsFilter.HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).isNull();
        assertThat(response.getContentAsString()).contains("official.not_member");
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
        member(OfficialRole.ANALYST);
        assertThat(run(acting("GET", "/api/votes/my"), new MockHttpServletResponse())).isNotNull();

        signIn();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(acting("POST", "/api/votes"), response)).isNull();
        assertThat(response.getContentAsString()).contains("official.role_forbidden");
    }

    @Test
    void shouldRefuseProfileEditWhenEditor() throws Exception {
        signIn();
        member(OfficialRole.EDITOR);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(acting("PATCH", "/api/users/me"), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(403);

        signIn();
        member(OfficialRole.ADMIN);
        assertThat(run(acting("PUT", "/api/users/me/avatar"), new MockHttpServletResponse())).isNotNull();
    }

    @Test
    void shouldRefuseAccountRoutesWhenActingEvenAsOwner() throws Exception {
        member(OfficialRole.OWNER);
        for (var call : List.of(List.of("POST", "/api/auth/passkey/register/options"), List.of("GET", "/api/users/me/sessions"),
                List.of("DELETE", "/api/users/me/passkeys/x"), List.of("GET", "/api/users/me/export"),
                List.of("POST", "/api/users/me/phone/send-otp"), List.of("POST", "/api/users/me/phone/verify"),
                List.of("DELETE", "/api/users/" + officialId))) {
            signIn();
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(run(acting(call.get(0), call.get(1)), response)).as(call.toString()).isNull();
            assertThat(response.getContentAsString()).contains("official.role_forbidden");
        }
        // Reading a public profile is not account deletion
        signIn();
        assertThat(run(acting("GET", "/api/users/alice"), new MockHttpServletResponse())).isNotNull();
    }
}

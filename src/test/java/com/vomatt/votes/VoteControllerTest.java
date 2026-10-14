package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.votes.dto.CreateVoteRequest;
import com.vomatt.votes.dto.InviteeResponse;
import com.vomatt.votes.dto.InviteesRequest;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteController")
class VoteControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    @Mock VoteService voteService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks VoteController voteController;

    MockMvc mockMvc;
    ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(voteController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver(),
                        new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate() {
        UserPrincipal principal = new UserPrincipal(USER_ID, "test@test.com", List.of("user"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    /** Mimics SecurityConfig: whether the path matches any public allowlist (accessible without sign-in) */
    private static boolean isPublic(String method, String path) {
        AntPathMatcher matcher = new AntPathMatcher();
        // authenticated sub-paths are matched before PUBLIC_GET
        if (Stream.of(SecurityEndpoints.AUTHENTICATED_USERS, SecurityEndpoints.AUTHENTICATED_VOTES)
                .flatMap(Arrays::stream).anyMatch(p -> matcher.match(p, path))) {
            return false;
        }
        Stream<String> patterns = Stream.of(SecurityEndpoints.PUBLIC_AUTH, SecurityEndpoints.PUBLIC_SWAGGER,
                SecurityEndpoints.PUBLIC_ACTUATOR).flatMap(Arrays::stream);
        if ("GET".equals(method)) {
            patterns = Stream.concat(patterns, Arrays.stream(SecurityEndpoints.PUBLIC_GET));
        }
        return patterns.anyMatch(p -> matcher.match(p, path));
    }

    // ─── GET /api/votes ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/votes")
    class GetActiveVotesTests {

        @Test
        @DisplayName("Should return 200 and paginated votes (public endpoint)")
        void shouldReturn200WithVoteList() throws Exception {
            VoteResponse vote = new VoteResponse();
            vote.setId(UUID.randomUUID().toString());
            vote.setTitle("Test Vote");
            when(voteService.listVotes(eq(VoteListOrder.NEWEST), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(new CursorResponse<>(List.of(vote), "next"));

            mockMvc.perform(get("/api/votes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items[0].title").value("Test Vote"))
                    .andExpect(jsonPath("$.data.nextCursor").value("next"));

            assertThat(isPublic("GET", "/api/votes")).isTrue();
        }

        @Test
        @DisplayName("Should pass status, sort, tag and cursor through")
        void shouldFilterByTag() throws Exception {
            when(voteService.listVotes(VoteListOrder.CLOSING, "tech", null, "c1", 10, null))
                    .thenReturn(new CursorResponse<>(List.of(), null));

            mockMvc.perform(get("/api/votes").param("status", "open").param("sort", "closing")
                            .param("tag", "tech").param("cursor", "c1").param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        }

        @Test
        @DisplayName("should return 400 when status=ended is given with sort")
        void shouldRejectSortWhenStatusEnded() throws Exception {
            mockMvc.perform(get("/api/votes").param("status", "ended").param("sort", "newest"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_LIST_SORT_NOT_ALLOWED.code()));
        }
    }

    // ─── GET /api/votes/{voteId} ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/votes/{voteId}")
    class GetVoteTests {

        @Test
        @DisplayName("Should return 200 when vote is found")
        void shouldReturn200WhenFound() throws Exception {
            String voteId = UUID.randomUUID().toString();
            VoteResponse vote = new VoteResponse();
            vote.setId(voteId);
            vote.setTitle("Test");
            when(voteService.getVote(voteId, null)).thenReturn(vote);

            mockMvc.perform(get("/api/votes/{voteId}", voteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(voteId));
        }

        @Test
        @DisplayName("Should return 404 when vote does not exist")
        void shouldReturn404WhenNotFound() throws Exception {
            String voteId = UUID.randomUUID().toString();
            when(voteService.getVote(voteId, null)).thenThrow(ApiException.notFound(MessageKey.VOTE_NOT_FOUND));

            mockMvc.perform(get("/api/votes/{voteId}", voteId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_NOT_FOUND.code()));
        }

        @Test
        @DisplayName("Should return 400 when voteId is not a valid UUID")
        void shouldReturn400WhenInvalidUuid() throws Exception {
            // VoteService parses id with UUID.fromString; invalid strings throw IllegalArgumentException -> 400
            when(voteService.getVote("not-a-uuid", null))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

            mockMvc.perform(get("/api/votes/{voteId}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_BAD_REQUEST.code()));
        }
    }

    // ─── /api/votes/{voteId}/invitees ─────────────────────────────────────────

    @Nested
    @DisplayName("/api/votes/{voteId}/invitees")
    class InviteeTests {

        private final String voteId = UUID.randomUUID().toString();
        private final String inviteeId = UUID.randomUUID().toString();

        @Test
        @DisplayName("should require sign-in when managing Invitees")
        void shouldRequireAuthenticationWhenManagingInvitees() {
            assertThat(isPublic("GET", "/api/votes/" + voteId + "/invitees")).isFalse();
            assertThat(isPublic("POST", "/api/votes/" + voteId + "/invitees")).isFalse();
            assertThat(isPublic("DELETE", "/api/votes/" + voteId + "/invitees/" + inviteeId)).isFalse();
            assertThat(isPublic("DELETE", "/api/votes/" + voteId + "/invitees")).isFalse();
        }

        @Test
        @DisplayName("should return 200 with Invitees and their Ballot flag when the owner lists them")
        void shouldReturn200WhenOwnerListsInvitees() throws Exception {
            authenticate();
            when(voteService.getInvitees(voteId, USER_ID))
                    .thenReturn(List.of(new InviteeResponse(inviteeId, "bob", null, true, null)));

            mockMvc.perform(get("/api/votes/{voteId}/invitees", voteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].userId").value(inviteeId))
                    .andExpect(jsonPath("$.data[0].hasBallot").value(true));
        }

        @Test
        @DisplayName("should return 200 with the new list when the owner adds Invitees")
        void shouldReturn200WhenOwnerAddsInvitees() throws Exception {
            authenticate();
            when(voteService.addInvitees(eq(voteId), any(InviteesRequest.class), eq(USER_ID)))
                    .thenReturn(List.of(new InviteeResponse(inviteeId, "bob", null, false, null)));

            mockMvc.perform(post("/api/votes/{voteId}/invitees", voteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userIds\": [\"" + inviteeId + "\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].username").value("bob"));
        }

        @Test
        @DisplayName("should return 400 when adding Invitees with an empty list")
        void shouldReturn400WhenUserIdsEmpty() throws Exception {
            authenticate();

            mockMvc.perform(post("/api/votes/{voteId}/invitees", voteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userIds\": []}"))
                    .andExpect(status().isBadRequest());
            verify(voteService, never()).addInvitees(any(), any(), any());
        }

        private String ids(int count) {
            return Stream.generate(() -> "\"" + UUID.randomUUID() + "\"").limit(count).collect(Collectors.joining(","));
        }

        private void assertValidationFailed(String path, String body) throws Exception {
            authenticate();
            mockMvc.perform(post(path, voteId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
            verify(voteService, never()).addInvitees(any(), any(), any());
            verify(voteService, never()).createVote(any(), any());
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when adding more than 100 Invitees")
        void shouldReturn400WhenTooManyUserIds() throws Exception {
            assertValidationFailed("/api/votes/{voteId}/invitees", "{\"userIds\": [" + ids(101) + "]}");
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when an added Invitee ID is null")
        void shouldReturn400WhenUserIdNull() throws Exception {
            assertValidationFailed("/api/votes/{voteId}/invitees", "{\"userIds\": [null]}");
        }

        @Test
        @DisplayName("should return 200 when adding Invitees with only groupIds")
        void shouldReturn200WhenOnlyGroupIdsAdded() throws Exception {
            authenticate();
            when(voteService.addInvitees(eq(voteId), any(InviteesRequest.class), eq(USER_ID))).thenReturn(List.of());

            mockMvc.perform(post("/api/votes/{voteId}/invitees", voteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupIds\": [\"" + UUID.randomUUID() + "\"]}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when adding more than 20 groupIds")
        void shouldReturn400WhenTooManyGroupIds() throws Exception {
            assertValidationFailed("/api/votes/{voteId}/invitees", "{\"groupIds\": [" + ids(21) + "]}");
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when creating with a null groupId")
        void shouldReturn400WhenGroupIdNullOnCreate() throws Exception {
            assertValidationFailed("/api/votes", POLL_BODY + "], \"groupIds\": [null]}");
        }

        private static final String POLL_BODY = "{\"title\":\"Q\",\"endTime\":\"2099-01-01T00:00:00Z\","
                + "\"options\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"audience\":\"INVITE_ONLY\",\"inviteeIds\":[";

        @Test
        @DisplayName("should return 400 common.validation_failed when creating with more than 100 inviteeIds")
        void shouldReturn400WhenTooManyInviteeIdsOnCreate() throws Exception {
            assertValidationFailed("/api/votes", POLL_BODY + ids(101) + "]}");
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when creating with a null inviteeId")
        void shouldReturn400WhenInviteeIdNullOnCreate() throws Exception {
            assertValidationFailed("/api/votes", POLL_BODY + "null]}");
        }

        @Test
        @DisplayName("should return 400 vote.invitee.has_ballot when the Invitee holds a Ballot")
        void shouldReturn400WhenInviteeHoldsBallot() throws Exception {
            authenticate();
            when(voteService.removeInvitee(voteId, inviteeId, USER_ID))
                    .thenThrow(ApiException.badRequest(MessageKey.VOTE_INVITEE_HAS_BALLOT));

            mockMvc.perform(delete("/api/votes/{voteId}/invitees/{userId}", voteId, inviteeId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_INVITEE_HAS_BALLOT.code()));
        }

        @Test
        @DisplayName("should return 200 when the owner adds only email Invitees")
        void shouldReturn200WhenOwnerAddsEmailInvitees() throws Exception {
            authenticate();
            when(voteService.addInvitees(eq(voteId), any(InviteesRequest.class), eq(USER_ID)))
                    .thenReturn(List.of(new InviteeResponse(null, null, "carol@example.com", false, null)));

            mockMvc.perform(post("/api/votes/{voteId}/invitees", voteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emails\": [\"carol@example.com\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].email").value("carol@example.com"))
                    .andExpect(jsonPath("$.data[0].userId").doesNotExist());
        }

        @Test
        @DisplayName("should trim and lower-case added emails before validating them, like sign-in")
        void shouldNormalizeEmailsWhenPadded() throws Exception {
            authenticate();

            mockMvc.perform(post("/api/votes/{voteId}/invitees", voteId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emails\": [\"  Carol@Example.COM \"]}"))
                    .andExpect(status().isOk());
            verify(voteService).addInvitees(eq(voteId), argThat(r -> r.getEmails().equals(List.of("carol@example.com"))), eq(USER_ID));
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when adding with neither userIds nor emails")
        void shouldReturn400WhenNoInviteesGiven() throws Exception {
            assertValidationFailed("/api/votes/{voteId}/invitees", "{\"emails\": []}");
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when an added email is invalid")
        void shouldReturn400WhenEmailInvalid() throws Exception {
            assertValidationFailed("/api/votes/{voteId}/invitees", "{\"emails\": [\"not-an-email\"]}");
        }

        @Test
        @DisplayName("should return 400 common.validation_failed when creating with an invalid inviteeEmail")
        void shouldReturn400WhenInviteeEmailInvalidOnCreate() throws Exception {
            assertValidationFailed("/api/votes", POLL_BODY + "], \"inviteeEmails\": [\"not-an-email\"]}");
        }

        @Test
        @DisplayName("should return 200 when the owner removes a pending email Invitee")
        void shouldReturn200WhenOwnerRemovesEmailInvitee() throws Exception {
            authenticate();
            when(voteService.removeEmailInvitee(voteId, "carol@example.com", USER_ID)).thenReturn(List.of());

            mockMvc.perform(delete("/api/votes/{voteId}/invitees", voteId).param("email", "carol@example.com"))
                    .andExpect(status().isOk());
            verify(voteService).removeEmailInvitee(voteId, "carol@example.com", USER_ID);
        }
    }

    // ─── DELETE /api/votes/{voteId} ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/votes/{voteId}")
    class DeleteVoteTests {

        @Test
        @DisplayName("Unauthenticated request should be rejected: DELETE is not in any public whitelist")
        void shouldRequireAuthenticationWhenDeleting() {
            assertThat(isPublic("DELETE", "/api/votes/" + UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("Should return 200 when the owner deletes within the window")
        void shouldReturn200WhenOwnerDeletes() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();

            mockMvc.perform(delete("/api/votes/{voteId}", voteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
            verify(voteService).deleteVote(voteId, USER_ID);
        }

        @Test
        @DisplayName("Should return 400 vote.delete.window_passed when the window has passed")
        void shouldReturn400WhenWindowPassed() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();
            doThrow(ApiException.badRequest(MessageKey.VOTE_DELETE_WINDOW_PASSED, 20L))
                    .when(voteService).deleteVote(voteId, USER_ID);

            mockMvc.perform(delete("/api/votes/{voteId}", voteId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_DELETE_WINDOW_PASSED.code()));
        }

        @Test
        @DisplayName("Should return 403 vote.forbidden when the caller is not the owner")
        void shouldReturn403WhenNotOwner() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();
            doThrow(ApiException.forbidden(MessageKey.VOTE_FORBIDDEN)).when(voteService).deleteVote(voteId, USER_ID);

            mockMvc.perform(delete("/api/votes/{voteId}", voteId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_FORBIDDEN.code()));
        }
    }

    // ─── POST /api/votes ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/votes")
    class CreateVoteTests {

        @Test
        @DisplayName("Unauthenticated request should be rejected: POST /api/votes is not in any public whitelist")
        void shouldRequireAuthentication() {
            assertThat(isPublic("POST", "/api/votes")).isFalse();
        }

        @Test
        @DisplayName("Authenticated user should be able to create vote and return 201")
        void shouldReturn201WhenAuthenticated() throws Exception {
            authenticate();
            VoteResponse created = new VoteResponse();
            created.setId(UUID.randomUUID().toString());
            created.setTitle("Favourite Color?");
            when(voteService.createVote(any(CreateVoteRequest.class), eq(USER_ID)))
                    .thenReturn(created);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Favourite Color?");
            CreateVoteRequest.VoteOptionRequest opt1 = new CreateVoteRequest.VoteOptionRequest();
            opt1.setText("Red");
            CreateVoteRequest.VoteOptionRequest opt2 = new CreateVoteRequest.VoteOptionRequest();
            opt2.setText("Blue");
            request.setOptions(List.of(opt1, opt2));
            request.setEndTime(java.time.OffsetDateTime.now().plusDays(1));

            mockMvc.perform(post("/api/votes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Favourite Color?"));
        }

        @Test
        @DisplayName("should return 400 when endTime is missing")
        void shouldRejectCreateWhenEndTimeMissing() throws Exception {
            authenticate();

            mockMvc.perform(post("/api/votes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Q\",\"options\":[{\"text\":\"A\"},{\"text\":\"B\"}]}"))
                    .andExpect(status().isBadRequest());

            verify(voteService, never()).createVote(any(), any());
        }

        @Test
        @DisplayName("Should return 400 when request body validation fails")
        void shouldReturn400WhenInvalidBody() throws Exception {
            authenticate();

            mockMvc.perform(post("/api/votes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(voteService, never()).createVote(any(), any());
        }
    }

    // ─── GET /api/votes/my ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/votes/my")
    class GetMyVotesTests {

        @Test
        @DisplayName("Unauthenticated request should be rejected: /api/votes/my is not in any public whitelist")
        void shouldRequireAuthentication() {
            assertThat(isPublic("GET", "/api/votes/my")).isFalse();
        }

        @Test
        @DisplayName("Authenticated user should be able to retrieve their own votes")
        void shouldReturnUserVotesWhenAuthenticated() throws Exception {
            authenticate();
            when(voteService.getMyPolls(USER_ID, null, null, null))
                    .thenReturn(new CursorResponse<>(List.of(), null));

            mockMvc.perform(get("/api/votes/my"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ─── DELETE /api/votes/{voteId}/vote ──────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/votes/{voteId}/vote")
    class RetractTests {

        @Test
        @DisplayName("withdraw requires sign-in: not in any public allowlist")
        void shouldRequireAuthentication() {
            assertThat(isPublic("DELETE", "/api/votes/abc/vote")).isFalse();
        }

        @Test
        @DisplayName("should return 200 and Poll with null myOptionId when withdraw succeeds")
        void shouldReturn200WhenRetracted() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();
            VoteResponse vote = new VoteResponse();
            vote.setId(voteId);
            when(voteService.retract(voteId, USER_ID)).thenReturn(vote);

            mockMvc.perform(delete("/api/votes/{voteId}/vote", voteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(voteId))
                    .andExpect(jsonPath("$.data.myOptionId").isEmpty());
        }

        @Test
        @DisplayName("should return errorCode vote.ended when Poll has ended")
        void shouldReturnVoteEndedWhenPollEnded() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();
            when(voteService.retract(voteId, USER_ID)).thenThrow(ApiException.badRequest(MessageKey.VOTE_ENDED));

            mockMvc.perform(delete("/api/votes/{voteId}/vote", voteId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_ENDED.code()));
        }
    }

    // ─── public vs authenticated vote paths ───────────────────────────────────

    @Test
    @DisplayName("Poll detail, results, comments and replies are publicly readable; /my, vote status, voter list and comment writes require sign-in")
    void shouldExposeOnlyDetailResultsAndCommentReadsPublicly() {
        String id = UUID.randomUUID().toString();
        assertThat(isPublic("GET", "/api/votes/" + id)).isTrue();
        assertThat(isPublic("GET", "/api/votes/" + id + "/results")).isTrue();
        assertThat(isPublic("GET", "/api/votes/" + id + "/comments")).isTrue();
        assertThat(isPublic("GET", "/api/votes/" + id + "/comments/def/replies")).isTrue();
        assertThat(isPublic("GET", "/api/votes/my")).isFalse();
        assertThat(isPublic("GET", "/api/votes/" + id + "/my-vote-status")).isFalse();
        assertThat(isPublic("GET", "/api/votes/" + id + "/voters")).isFalse();
        assertThat(isPublic("POST", "/api/votes/" + id + "/comments")).isFalse();
        assertThat(isPublic("POST", "/api/votes/" + id + "/comments/def/like")).isFalse();
        assertThat(isPublic("PUT", "/api/votes/" + id + "/comments/def")).isFalse();
    }

    @Test
    @DisplayName("PUBLIC_GET alone does not permit named sub-paths, without relying on AUTHENTICATED_VOTES ordering first")
    void shouldKeepNamedVoteRoutesAuthenticated() {
        // Spring Security 7 matches with PathPatternParser
        PathPatternParser parser = PathPatternParser.defaultInstance;
        Predicate<String> publicGet = path -> Arrays.stream(SecurityEndpoints.PUBLIC_GET)
                .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath(path)));
        String id = UUID.randomUUID().toString();

        assertThat(publicGet.test("/api/votes/" + id)).isTrue();
        assertThat(publicGet.test("/api/votes/" + id + "/results")).isTrue();
        assertThat(publicGet.test("/api/votes/" + id + "/comments")).isTrue();
        assertThat(publicGet.test("/api/votes/my")).isFalse();
        assertThat(publicGet.test("/api/votes/notifications")).isFalse();
        assertThat(publicGet.test("/api/votes/my/comments")).isFalse();
        // hex-only route names are not UUIDs
        assertThat(publicGet.test("/api/votes/dead-beef")).isFalse();
        assertThat(publicGet.test("/api/votes/ad-feed/results")).isFalse();
    }
}

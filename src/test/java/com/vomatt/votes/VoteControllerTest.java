package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.votes.dto.CreateVoteRequest;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /** 模擬 SecurityConfig：路徑是否落在任一公開白名單（未登入可存取） */
    private static boolean isPublic(String method, String path) {
        AntPathMatcher matcher = new AntPathMatcher();
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
            Page<VoteResponse> page = new PageImpl<>(List.of(vote));
            when(voteService.getActiveVotes(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/votes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].title").value("Test Vote"))
                    .andExpect(jsonPath("$.data.page").value(1));

            assertThat(isPublic("GET", "/api/votes")).isTrue();
        }

        @Test
        @DisplayName("Should filter by tag slug when ?tag= is given")
        void shouldFilterByTag() throws Exception {
            when(voteService.getActiveVotesByTag(eq("tech"), any(Pageable.class))).thenReturn(Page.empty());

            mockMvc.perform(get("/api/votes").param("tag", "tech"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(voteService, never()).getActiveVotes(any());
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
            // VoteService 以 UUID.fromString 解析 id，無效字串丟 IllegalArgumentException → 400
            when(voteService.getVote("not-a-uuid", null))
                    .thenThrow(new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

            mockMvc.perform(get("/api/votes/{voteId}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_BAD_REQUEST.code()));
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

            mockMvc.perform(post("/api/votes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Favourite Color?"));
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
            when(voteService.getVotesByCreator(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

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
        @DisplayName("撤回需登入：不在任何公開白名單")
        void shouldRequireAuthentication() {
            assertThat(isPublic("DELETE", "/api/votes/abc/vote")).isFalse();
        }

        @Test
        @DisplayName("應該在撤回成功時回傳 200 與 myOptionId 為 null 的 Poll")
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
        @DisplayName("應該在 Poll 已結束時回傳 errorCode vote.ended")
        void shouldReturnVoteEndedWhenPollEnded() throws Exception {
            authenticate();
            String voteId = UUID.randomUUID().toString();
            when(voteService.retract(voteId, USER_ID)).thenThrow(ApiException.badRequest(MessageKey.VOTE_ENDED));

            mockMvc.perform(delete("/api/votes/{voteId}/vote", voteId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.VOTE_ENDED.code()));
        }
    }
}

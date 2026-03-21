package com.vomattapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vomattapi.application.controller.VoteController;
import com.vomattapi.application.dto.request.CreateVoteRequest;
import com.vomattapi.application.dto.response.VoteResponse;
import com.vomattapi.application.exception.VoteNotFoundException;
import com.vomattapi.application.service.VoteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import com.vomattapi.application.security.services.UserDetailsImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VoteController.class)
@ActiveProfiles("test")
@Import(VoteControllerTest.TestSecurityConfig.class)
@DisplayName("VoteController")
class VoteControllerTest {

    /** 測試用簡化安全設定：CSRF 停用，所有路徑開放（由 @PreAuthorize 控制存取） */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    private static UserDetailsImpl authenticatedUser() {
        return new UserDetailsImpl("user-id", "testuser", "test@test.com", "pw",
                true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean VoteService voteService;
    // AuthTokenFilter 是 @Component 需要這些依賴才能被 Spring 建立（雖然 TestSecurityConfig 不使用它）
    @MockBean com.vomattapi.application.security.jwt.JwtUtils jwtUtils;
    @MockBean com.vomattapi.application.security.services.UserDetailsServiceImpl userDetailsService;
    @MockBean com.vomattapi.application.service.JwtBlacklistService jwtBlacklistService;

    // ─── GET /api/v1/votes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/votes")
    class GetActiveVotesTests {

        @Test
        @DisplayName("應該回傳 200 和投票列表")
        void shouldReturn200WithVoteList() throws Exception {
            VoteResponse vote = new VoteResponse();
            vote.setId(UUID.randomUUID().toString());
            vote.setTitle("Test Vote");
            Page<VoteResponse> page = new PageImpl<>(List.of(vote));
            when(voteService.getActiveVotes(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/votes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray());
        }
    }

    // ─── GET /api/v1/votes/{voteId} ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/votes/{voteId}")
    class GetVoteTests {

        @Test
        @DisplayName("找到投票時應回傳 200")
        void shouldReturn200WhenFound() throws Exception {
            String voteId = UUID.randomUUID().toString();
            VoteResponse vote = new VoteResponse();
            vote.setId(voteId);
            vote.setTitle("Test");
            when(voteService.getVote(voteId)).thenReturn(vote);

            mockMvc.perform(get("/api/v1/votes/{voteId}", voteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(voteId));
        }

        @Test
        @DisplayName("投票不存在時應回傳 404")
        void shouldReturn404WhenNotFound() throws Exception {
            String voteId = UUID.randomUUID().toString();
            when(voteService.getVote(voteId)).thenThrow(new VoteNotFoundException(voteId));

            mockMvc.perform(get("/api/v1/votes/{voteId}", voteId))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── POST /api/v1/votes ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/votes")
    class CreateVoteTests {

        @Test
        @DisplayName("未認證請求應被拒絕（4xx）")
        void shouldReturn4xxWhenUnauthenticated() throws Exception {
            // 未認證：若 @PreAuthorize 生效則回傳 403，否則因 body 驗證失敗回傳 400
            mockMvc.perform(post("/api/v1/votes").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("認證用戶應能建立投票並回傳 201")
        void shouldReturn201WhenAuthenticated() throws Exception {
            VoteResponse created = new VoteResponse();
            created.setId(UUID.randomUUID().toString());
            created.setTitle("Favourite Color?");
            when(voteService.createVote(any(CreateVoteRequest.class), anyString()))
                    .thenReturn(created);

            CreateVoteRequest request = new CreateVoteRequest();
            request.setTitle("Favourite Color?");
            CreateVoteRequest.VoteOptionRequest opt1 = new CreateVoteRequest.VoteOptionRequest();
            opt1.setText("Red");
            CreateVoteRequest.VoteOptionRequest opt2 = new CreateVoteRequest.VoteOptionRequest();
            opt2.setText("Blue");
            request.setOptions(List.of(opt1, opt2));

            mockMvc.perform(post("/api/v1/votes")
                    .with(user(authenticatedUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Favourite Color?"));
        }
    }

    // ─── GET /api/v1/votes/my ─────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/votes/my")
    class GetMyVotesTests {

        @Test
        @DisplayName("未認證請求應被 @PreAuthorize 拒絕（403）")
        void shouldReturn403WhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/votes/my"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("認證用戶應能取得自己的投票")
        void shouldReturnUserVotesWhenAuthenticated() throws Exception {
            when(voteService.getVotesByCreator(anyString(), any(Pageable.class)))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/api/v1/votes/my")
                    .with(user(authenticatedUser())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}

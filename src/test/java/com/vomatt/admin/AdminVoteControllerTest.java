package com.vomatt.admin;

import com.vomatt.admin.dto.AdminVoteResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
import com.vomatt.entity.Audience;
import com.vomatt.votes.dto.VoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.vomatt.admin.AdminControllerTestSupport.ADMIN_ID;
import static com.vomatt.admin.AdminControllerTestSupport.authenticateAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminVoteController")
class AdminVoteControllerTest {

    @Mock AdminVoteService adminVoteService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    final UUID voteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminVoteController(adminVoteService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AdminVoteResponse response(OffsetDateTime deletedAt) {
        VoteResponse vote = new VoteResponse();
        vote.setId(voteId.toString());
        return new AdminVoteResponse(vote, deletedAt, "alice@example.com", true);
    }

    @Test
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminVoteController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void shouldPassFiltersWhenAdminListsPolls() throws Exception {
        authenticateAs("admin");
        UUID creatorId = UUID.randomUUID();
        when(adminVoteService.listVotes("lang", "open", creatorId, Audience.INVITE_ONLY, true, 2, 10))
                .thenReturn(PageResponse.of(List.of(response(OffsetDateTime.now())), 11, 2, 10));

        mockMvc.perform(get("/api/admin/votes").param("q", "lang").param("status", "open")
                        .param("creatorId", creatorId.toString()).param("audience", "INVITE_ONLY")
                        .param("deleted", "true").param("page", "2").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].vote.id").value(voteId.toString()))
                .andExpect(jsonPath("$.data.content[0].deletedAt").exists());
    }

    @Test
    void shouldReturn400WhenAudienceUnknown() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(get("/api/admin/votes").param("audience", "PRIVATE")).andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn403WhenUserRoleDeletesPoll() throws Exception {
        authenticateAs("user");

        mockMvc.perform(delete("/api/admin/votes/{id}", voteId)).andExpect(status().isForbidden());

        verify(adminVoteService, never()).deleteVote(any(), anyString());
    }

    @Test
    void shouldDeleteWhenAdmin() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(delete("/api/admin/votes/{id}", voteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        verify(adminVoteService).deleteVote(voteId, ADMIN_ID);
    }

    @Test
    void shouldReturnPollWhenAdminClosesOrRestores() throws Exception {
        authenticateAs("admin");
        when(adminVoteService.closeVote(voteId, ADMIN_ID)).thenReturn(response(null));
        when(adminVoteService.restoreVote(voteId, ADMIN_ID)).thenReturn(response(null));

        mockMvc.perform(put("/api/admin/votes/{id}/close", voteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vote.id").value(voteId.toString()));
        mockMvc.perform(patch("/api/admin/votes/{id}/restore", voteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorEmail").value("alice@example.com"));
    }
}

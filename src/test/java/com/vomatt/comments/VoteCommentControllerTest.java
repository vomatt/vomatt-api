package com.vomatt.comments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.dto.ReportRequest;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.entity.ReportReason;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoteCommentController")
class VoteCommentControllerTest {

    @Mock VoteCommentService commentService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks VoteCommentController commentController;

    MockMvc mockMvc;

    private final String voteId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(commentController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @Test
    @DisplayName("should return comments when not logged in (no user)")
    void shouldListCommentsWhenSignedOut() throws Exception {
        when(commentService.getCommentsByVote(eq(voteId), isNull(), isNull(), isNull()))
                .thenReturn(new CursorResponse<>(List.of(), null));

        mockMvc.perform(get("/api/votes/{voteId}/comments", voteId)).andExpect(status().isOk());

        verify(commentService).getCommentsByVote(eq(voteId), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("should return replies when not logged in (no user)")
    void shouldListRepliesWhenSignedOut() throws Exception {
        UUID commentId = UUID.randomUUID();
        when(commentService.getReplies(eq(voteId), eq(commentId), isNull(), isNull(), isNull()))
                .thenReturn(new CursorResponse<>(List.of(), null));

        mockMvc.perform(get("/api/votes/{voteId}/comments/{commentId}/replies", voteId, commentId))
                .andExpect(status().isOk());
    }

    private void signIn(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, List.of("user"), null), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should pass the reason and note to the service when a Comment is reported")
    void shouldReportCommentWhenBodyValid() throws Exception {
        String userId = UUID.randomUUID().toString();
        UUID commentId = UUID.randomUUID();
        signIn(userId);

        mockMvc.perform(post("/api/votes/{voteId}/comments/{commentId}/report", voteId, commentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"HATE\",\"note\":\"slurs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        verify(commentService).reportComment(commentId, userId, new ReportRequest(ReportReason.HATE, "slurs"));
    }

    @Test
    @DisplayName("should return 400 without calling the service when the reason is missing or the note too long")
    void shouldRejectReportWhenBodyInvalid() throws Exception {
        signIn(UUID.randomUUID().toString());
        UUID commentId = UUID.randomUUID();

        mockMvc.perform(post("/api/votes/{voteId}/comments/{commentId}/report", voteId, commentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"no reason\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        mockMvc.perform(post("/api/votes/{voteId}/comments/{commentId}/report", voteId, commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\",\"note\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        mockMvc.perform(post("/api/votes/{voteId}/comments/{commentId}/report", voteId, commentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"RUDE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_BAD_REQUEST.code()));

        verify(commentService, never()).reportComment(any(), any(), any());
    }
}

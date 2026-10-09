package com.vomatt.comments;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.CursorResponse;

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
    @DisplayName("應該在未登入時回傳留言（不帶使用者）")
    void shouldListCommentsWhenSignedOut() throws Exception {
        when(commentService.getCommentsByVote(eq(voteId), isNull(), isNull(), isNull()))
                .thenReturn(new CursorResponse<>(List.of(), null));

        mockMvc.perform(get("/api/votes/{voteId}/comments", voteId)).andExpect(status().isOk());

        verify(commentService).getCommentsByVote(eq(voteId), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("應該在未登入時回傳回覆（不帶使用者）")
    void shouldListRepliesWhenSignedOut() throws Exception {
        UUID commentId = UUID.randomUUID();
        when(commentService.getReplies(eq(voteId), eq(commentId), isNull(), isNull(), isNull()))
                .thenReturn(new CursorResponse<>(List.of(), null));

        mockMvc.perform(get("/api/votes/{voteId}/comments/{commentId}/replies", voteId, commentId))
                .andExpect(status().isOk());
    }
}

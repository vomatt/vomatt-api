package com.vomatt.admin;

import com.vomatt.admin.dto.AdminCommentResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommentController")
class AdminCommentControllerTest {

    @Mock AdminCommentService adminCommentService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminCommentController(adminCommentService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminCommentController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void shouldReturnDeletedCommentTextWhenAdminLists() throws Exception {
        authenticateAs("admin");
        UUID voteId = UUID.randomUUID();
        when(adminCommentService.listComments(voteId, null, null, true, null, null)).thenReturn(PageResponse.of(List.of(
                new AdminCommentResponse("c1", voteId.toString(), "Poll", null, "u1", "alice", "spam!", true, null, null)),
                1, 1, 20));

        mockMvc.perform(get("/api/admin/comments").param("voteId", voteId.toString()).param("deleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].content").value("spam!"))
                .andExpect(jsonPath("$.data.content[0].deleted").value(true));
    }

    @Test
    void shouldDeleteWhenAdmin() throws Exception {
        authenticateAs("admin");
        UUID commentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/comments/{id}", commentId)).andExpect(status().isOk());

        verify(adminCommentService).deleteComment(commentId, ADMIN_ID);
    }

    @Test
    void shouldReturn403WhenModeratorDeletes() throws Exception {
        authenticateAs("moderator");

        mockMvc.perform(delete("/api/admin/comments/{id}", UUID.randomUUID())).andExpect(status().isForbidden());

        verify(adminCommentService, never()).deleteComment(any(), anyString());
    }
}

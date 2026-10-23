package com.vomatt.admin;

import static com.vomatt.admin.AdminControllerTestSupport.authenticateAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

import com.vomatt.admin.dto.AdminFeedbackResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
import com.vomatt.feedback.FeedbackService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFeedbackController")
class AdminFeedbackControllerTest {

    @Mock FeedbackService feedbackService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminFeedbackController(feedbackService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminFeedbackController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void shouldReturnFeedbackWhenAdminLists() throws Exception {
        authenticateAs("admin");
        when(feedbackService.listFeedback(null, null, null)).thenReturn(PageResponse.of(List.of(
                new AdminFeedbackResponse("f1", "u1", "alice", "Please add dark mode.", null)), 1, 1, 20));

        mockMvc.perform(get("/api/admin/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].content").value("Please add dark mode."));
    }

    @Test
    void shouldReturn403WhenModeratorLists() throws Exception {
        authenticateAs("moderator");

        mockMvc.perform(get("/api/admin/feedback")).andExpect(status().isForbidden());

        verify(feedbackService, never()).listFeedback(any(), any(), any());
    }
}

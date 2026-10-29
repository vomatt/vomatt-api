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

import com.vomatt.admin.dto.AdminCommentReportResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
import com.vomatt.entity.ReportReason;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommentReportController")
class AdminCommentReportControllerTest {

    @Mock AdminCommentReportService reportService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminCommentReportController(reportService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should require role ADMIN on the whole controller")
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminCommentReportController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("should return the page of reports when an admin lists them")
    void shouldReturnReportsWhenAdminLists() throws Exception {
        authenticateAs("admin");
        when(reportService.listReports(2, 10)).thenReturn(PageResponse.of(List.of(new AdminCommentReportResponse(
                "r1", "c1", "v1", null, "rude", false, "u1", "troll", "u2", "alice", ReportReason.HARASSMENT,
                null, null)), 11, 2, 10));

        mockMvc.perform(get("/api/admin/comment-reports").param("page", "2").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reason").value("HARASSMENT"))
                .andExpect(jsonPath("$.data.content[0].reporterUsername").value("alice"));
    }

    @Test
    @DisplayName("should return 403 when a moderator lists reports")
    void shouldReturn403WhenModeratorLists() throws Exception {
        authenticateAs("moderator");

        mockMvc.perform(get("/api/admin/comment-reports")).andExpect(status().isForbidden());

        verify(reportService, never()).listReports(any(), any());
    }
}

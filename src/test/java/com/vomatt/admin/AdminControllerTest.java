package com.vomatt.admin;

import com.vomatt.admin.dto.AdminStatsResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vomatt.admin.AdminControllerTestSupport.authenticateAs;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController")
class AdminControllerTest {

    @Mock AdminService adminService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminController(adminService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnCountsWhenAdminGetsStats() throws Exception {
        authenticateAs("admin");
        when(adminService.getStats()).thenReturn(new AdminStatsResponse(10, 2, 1, 3, 4, 5, 6));

        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usersTotal").value(10))
                .andExpect(jsonPath("$.data.ballotsLast24Hours").value(6));
    }

    @Test
    void shouldReturn403WhenUserRoleGetsStats() throws Exception {
        authenticateAs("user");

        mockMvc.perform(get("/api/admin/stats")).andExpect(status().isForbidden());

        verify(adminService, never()).getStats();
    }

    @Test
    void shouldParseIsoRangeWhenListingAuditLogs() throws Exception {
        authenticateAs("admin");
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-10-17T00:00:00+08:00");
        when(adminService.listAuditLogs(null, "SUSPEND", null, null, from, to, null, null))
                .thenReturn(PageResponse.of(List.of(), 0, 1, 20));

        mockMvc.perform(get("/api/admin/audit-logs").param("action", "SUSPEND")
                        .param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-17T00:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldReturn400WhenFilterMalformed() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(get("/api/admin/audit-logs").param("from", "yesterday")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/audit-logs").param("userId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.type_mismatch"));
        mockMvc.perform(get("/api/admin/audit-logs").param("resourceId", "42")).andExpect(status().isBadRequest());

        verify(adminService, never()).listAuditLogs(any(), any(), any(), any(), any(), any(), any(), any());
    }
}

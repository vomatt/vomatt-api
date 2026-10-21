package com.vomatt.admin;

import com.vomatt.admin.dto.AdminUserDetail;
import com.vomatt.admin.dto.AdminUserResponse;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserController")
class AdminUserControllerTest {

    @Mock AdminUserService adminUserService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminUserController(adminUserService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AdminUserDetail detail(boolean active) {
        return new AdminUserDetail(userId.toString(), "alice", null, null, null, null, "alice@example.com", null, "email_otp",
                List.of("user"), active, null, 0, "BASIC", null, 1, 2, 3);
    }

    @Test
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminUserController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void shouldReturnPageWhenAdminListsUsers() throws Exception {
        authenticateAs("admin");
        when(adminUserService.listUsers("ali", "active", null, 1, 20)).thenReturn(PageResponse.of(List.of(
                new AdminUserResponse(userId.toString(), "alice", null, null, null, null, null, null, null, List.of("user"), true, null, null)),
                1, 1, 20));

        mockMvc.perform(get("/api/admin/users").param("q", "ali").param("status", "active").param("page", "1").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("alice"));
    }

    @Test
    void shouldReturn403WhenUserRoleListsUsers() throws Exception {
        authenticateAs("user");

        mockMvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());

        verify(adminUserService, never()).listUsers(any(), any(), any(), any(), any());
    }

    @Test
    void shouldSuspendWithCallerIdWhenReasonGiven() throws Exception {
        authenticateAs("admin");
        when(adminUserService.suspend(userId, ADMIN_ID)).thenReturn(detail(false));

        mockMvc.perform(patch("/api/admin/users/{id}/suspend", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"spam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.commentCount").value(3));
    }

    @Test
    void shouldSuspendWhenBodyOmitted() throws Exception {
        authenticateAs("admin");
        when(adminUserService.suspend(userId, ADMIN_ID)).thenReturn(detail(false));

        mockMvc.perform(patch("/api/admin/users/{id}/suspend", userId)).andExpect(status().isOk());
    }

    @Test
    void shouldReturn400WhenSuspendReasonTooLong() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(patch("/api/admin/users/{id}/suspend", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(adminUserService, never()).suspend(any(), anyString());
    }

    @Test
    void shouldUnsuspendWhenAdmin() throws Exception {
        authenticateAs("admin");
        when(adminUserService.unsuspend(userId, ADMIN_ID)).thenReturn(detail(true));

        mockMvc.perform(patch("/api/admin/users/{id}/unsuspend", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void shouldSetRolesWhenAllRolesKnown() throws Exception {
        authenticateAs("admin");
        when(adminUserService.updateRoles(userId, List.of("user", "moderator"), ADMIN_ID)).thenReturn(detail(true));

        mockMvc.perform(put("/api/admin/users/{id}/roles", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"user\",\"moderator\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn400WhenRoleUnknown() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(put("/api/admin/users/{id}/roles", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"superuser\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/users/{id}/roles", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[]}"))
                .andExpect(status().isBadRequest());

        verify(adminUserService, never()).updateRoles(any(), any(), anyString());
    }

    @Test
    void shouldReturn400WhenUserIdMalformed() throws Exception {
        authenticateAs("admin");

        mockMvc.perform(get("/api/admin/users/not-a-uuid")).andExpect(status().isBadRequest());
    }
}

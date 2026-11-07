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

import com.vomatt.admin.dto.AdminContactMessageResponse;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.PageResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminContactMessageController")
class AdminContactMessageControllerTest {

    @Mock AdminContactMessageService contactMessageService;
    @Mock LocalizedMessageService messageService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminContactMessageController(contactMessageService), messageService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldHaveClassLevelAdminPreAuthorizeWhenInspected() {
        assertThat(AdminContactMessageController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void shouldAuditAsContactMessageViewWhenInspected() throws Exception {
        Auditable auditable = AdminContactMessageController.class
                .getMethod("listMessages", Integer.class, Integer.class).getAnnotation(Auditable.class);
        assertThat(auditable.action()).isEqualTo("VIEW");
        assertThat(auditable.resourceType()).isEqualTo("CONTACT_MESSAGE");
    }

    @Test
    void shouldReturnMessagesWhenAdminLists() throws Exception {
        authenticateAs("admin");
        when(contactMessageService.listMessages(2, 10)).thenReturn(PageResponse.of(List.of(
                new AdminContactMessageResponse("m1", "Alice", "alice@example.com", "Hello", "zh", null)), 11, 2, 10));

        mockMvc.perform(get("/api/admin/contact-messages").param("page", "2").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.content[0].locale").value("zh"));
    }

    @Test
    void shouldReturn403WhenModeratorLists() throws Exception {
        authenticateAs("moderator");

        mockMvc.perform(get("/api/admin/contact-messages")).andExpect(status().isForbidden());

        verify(contactMessageService, never()).listMessages(any(), any());
    }
}

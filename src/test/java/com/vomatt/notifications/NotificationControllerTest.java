package com.vomatt.notifications;

import static org.mockito.Mockito.doThrow;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.notifications.dto.UnreadCountResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationController")
class NotificationControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    @Mock NotificationService notificationService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks NotificationController controller;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(USER_ID, "u@test.local", List.of("user")), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("應該回傳未讀數")
    void shouldReturnUnreadCount() throws Exception {
        when(notificationService.countUnread(USER_ID)).thenReturn(new UnreadCountResponse(3));

        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @DisplayName("應該標記已讀")
    void shouldMarkRead() throws Exception {
        String voteId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/notifications/{voteId}/read", voteId))
                .andExpect(status().isOk());

        verify(notificationService).markRead(USER_ID, voteId);
    }

    @Test
    @DisplayName("應該在沒有該通知時回 404")
    void shouldReturn404WhenNoNotification() throws Exception {
        String voteId = UUID.randomUUID().toString();
        doThrow(ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND))
                .when(notificationService).markRead(USER_ID, voteId);

        mockMvc.perform(post("/api/notifications/{voteId}/read", voteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.NOTIFICATION_NOT_FOUND.code()));
    }
}

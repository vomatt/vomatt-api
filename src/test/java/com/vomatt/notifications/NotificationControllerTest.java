package com.vomatt.notifications;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import com.vomatt.common.response.CursorResponse;
import com.vomatt.entity.NotificationKind;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.notifications.dto.UnreadCountResponse;
import com.vomatt.users.dto.UserDto;

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
                new UserPrincipal(USER_ID, List.of("user")), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should return the unread count")
    void shouldReturnUnreadCount() throws Exception {
        when(notificationService.countUnread(USER_ID)).thenReturn(new UnreadCountResponse(3));

        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @DisplayName("should list the feed with the cursor and limit given")
    void shouldListNotificationsWhenRequested() throws Exception {
        NotificationResponse followed = new NotificationResponse("followed_" + UUID.randomUUID(), NotificationKind.FOLLOWED,
                OffsetDateTime.now(), false, new UserDto(UUID.randomUUID().toString(), "jtan", null, null), null, null);
        when(notificationService.list(USER_ID, "abc", 5)).thenReturn(new CursorResponse<>(List.of(followed), "next"));

        mockMvc.perform(get("/api/notifications").param("cursor", "abc").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].kind").value("FOLLOWED"))
                .andExpect(jsonPath("$.data.items[0].actor.username").value("jtan"))
                .andExpect(jsonPath("$.data.nextCursor").value("next"));
    }

    @Test
    @DisplayName("should mark as read by notification id or deprecated Poll id")
    void shouldMarkRead() throws Exception {
        String voteId = UUID.randomUUID().toString();
        String id = "comment_" + UUID.randomUUID();

        mockMvc.perform(post("/api/notifications/{id}/read", voteId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/notifications/{id}/read", id)).andExpect(status().isOk());

        verify(notificationService).markRead(USER_ID, voteId);
        verify(notificationService).markRead(USER_ID, id);
    }

    @Test
    @DisplayName("should mark everything read")
    void shouldMarkAllReadWhenRequested() throws Exception {
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isOk());

        verify(notificationService).markAllRead(USER_ID);
    }

    @Test
    @DisplayName("should return 404 when the notification does not exist")
    void shouldReturn404WhenNoNotification() throws Exception {
        String voteId = UUID.randomUUID().toString();
        doThrow(ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND))
                .when(notificationService).markRead(USER_ID, voteId);

        mockMvc.perform(post("/api/notifications/{voteId}/read", voteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.NOTIFICATION_NOT_FOUND.code()));
    }
}

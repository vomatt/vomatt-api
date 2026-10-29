package com.vomatt.notifications;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.notifications.dto.NotificationPreferencesResponse;
import com.vomatt.notifications.dto.UpdateNotificationPreferencesRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPreferenceController")
class NotificationPreferenceControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    @Mock NotificationService notificationService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks NotificationPreferenceController controller;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(USER_ID, List.of("user")), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should return the caller's switches")
    void shouldReturnPreferencesWhenRequested() throws Exception {
        when(notificationService.getPreferences(USER_ID)).thenReturn(new NotificationPreferencesResponse(true, true, true, false, true));

        mockMvc.perform(get("/api/users/me/notification-preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultsIn").value(true))
                .andExpect(jsonPath("$.data.closingSoon").value(false));
    }

    @Test
    @DisplayName("should pass omitted switches as null when patching")
    void shouldPassOmittedSwitchesAsNullWhenPatching() throws Exception {
        when(notificationService.updatePreferences(USER_ID, new UpdateNotificationPreferencesRequest(null, null, false, true, null)))
                .thenReturn(new NotificationPreferencesResponse(true, true, false, true, true));

        mockMvc.perform(patch("/api/users/me/notification-preferences")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comments\":false,\"closingSoon\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments").value(false))
                .andExpect(jsonPath("$.data.closingSoon").value(true));
    }
}

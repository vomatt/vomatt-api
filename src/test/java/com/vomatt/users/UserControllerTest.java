package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.pattern.PathPatternParser;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.users.dto.UserDto;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.dto.VoteResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController")
class UserControllerTest {

    @Mock UserService userService;
    @Mock VoteService voteService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks UserController userController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @Test
    @DisplayName("should return user's public Poll list when not signed in")
    void shouldListUserPollsWhenSignedOut() throws Exception {
        VoteResponse poll = new VoteResponse();
        poll.setTitle("Lunch?");
        when(voteService.getUserPolls(eq("alice"), isNull(), isNull(), isNull()))
                .thenReturn(new CursorResponse<>(List.of(poll), "next"));

        mockMvc.perform(get("/api/users/alice/votes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("Lunch?"))
                .andExpect(jsonPath("$.data.nextCursor").value("next"));
    }

    @Test
    @DisplayName("should return only public profile fields when searching users")
    void shouldReturnOnlyPublicFieldsWhenSearchingUsers() throws Exception {
        when(userService.searchUsersByUsername(eq("ali"), isNull(), isNull())).thenReturn(new CursorResponse<>(
                List.of(new UserDto("0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "alice", "Alice", null)), null));

        mockMvc.perform(get("/api/users/search").param("username", " ali "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].*", hasSize(4)))
                .andExpect(jsonPath("$.data.items[0].username").value("alice"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("Alice"))
                .andExpect(jsonPath("$.data.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].phoneNumber").doesNotExist());
    }

    @Test
    @DisplayName("should reject user search when username is blank")
    void shouldRejectUserSearchWhenUsernameBlank() throws Exception {
        mockMvc.perform(get("/api/users/search").param("username", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.missing_param"));
    }

    @Test
    @DisplayName("should make /api/users/{username}/votes public while /api/users/me/votes still requires sign-in")
    void shouldExposeUserPollsPublicly() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        assertThat(Arrays.stream(SecurityEndpoints.PUBLIC_GET)
                .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath("/api/users/alice/votes")))).isTrue();
        assertThat(Arrays.stream(SecurityEndpoints.AUTHENTICATED_USERS)
                .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath("/api/users/me/votes")))).isTrue();
        assertThat(Arrays.stream(SecurityEndpoints.AUTHENTICATED_USERS)
                .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath("/api/users/search/votes")))).isTrue();
    }
}

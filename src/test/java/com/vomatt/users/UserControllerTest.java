package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.pattern.PathPatternParser;

import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
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

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "alice@example.com", List.of("user")), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    @DisplayName("should accept every bot kind as avatarUrl when updating profile")
    void shouldAcceptAvatarUrlWhenBotKindKnown() throws Exception {
        signIn("u1");
        when(userService.updateMyProfile(eq("u1"), any())).thenAnswer(inv -> new UserProfileResponse("alice", null, null,
                inv.<com.vomatt.users.dto.UpdateProfileRequest>getArgument(1).getAvatarUrl(), null, 0, 0,
                null, null, null, null, null, null, null));

        for (String kind : BotAvatar.KINDS) {
            mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"avatarUrl\":\"bot:" + kind + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avatarUrl").value("bot:" + kind));
        }
        assertThat(BotAvatar.KINDS).hasSize(48).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("should accept bot:none as avatarUrl when updating profile")
    void shouldAcceptAvatarUrlWhenNone() throws Exception {
        signIn("u1");
        when(userService.updateMyProfile(eq("u1"), any())).thenAnswer(inv -> new UserProfileResponse("alice", null, null,
                inv.<com.vomatt.users.dto.UpdateProfileRequest>getArgument(1).getAvatarUrl(), null, 0, 0,
                null, null, null, null, null, null, null));

        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\":\"bot:none\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("bot:none"));
        assertThat(BotAvatar.KINDS).doesNotContain("none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bot:unicorn", "boba", "bot:", "none", "bot:None", "bot:Boba", "bot:boba ", "https://cdn.example.com/a.png", ""})
    @DisplayName("should reject avatarUrl when it is not a known bot kind")
    void shouldRejectAvatarUrlWhenNotBotKind(String avatarUrl) throws Exception {
        signIn("u1");
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\":\"" + avatarUrl + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("avatarUrl:")));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("should leave avatarUrl unchanged when omitted")
    void shouldPassNullAvatarUrlWhenOmitted() throws Exception {
        signIn("u1");
        when(userService.updateMyProfile(eq("u1"), argThat(r -> r.getAvatarUrl() == null && "Al".equals(r.getDisplayName()))))
                .thenReturn(new UserProfileResponse("alice", "Al", null, "bot:boba", null, 0, 0,
                        null, null, null, null, null, null, null));

        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Al\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("bot:boba"));
    }

    @Test
    @DisplayName("should pass name and birthday to the service when updating profile")
    void shouldPassNameAndBirthdayWhenUpdatingProfile() throws Exception {
        signIn("u1");
        when(userService.updateMyProfile(eq("u1"), argThat(r -> "Alice".equals(r.getFirstName())
                && "Chen".equals(r.getLastName()) && java.time.LocalDate.of(1995, 4, 12).equals(r.getBirthday()))))
                .thenReturn(new UserProfileResponse("alice", null, null, "bot:boba", null, 0, 0,
                        null, "Alice", "Chen", java.time.LocalDate.of(1995, 4, 12), null, null, null));

        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alice\",\"lastName\":\"Chen\",\"birthday\":\"1995-04-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthday").value("1995-04-12"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"firstName", "lastName"})
    @DisplayName("should reject a name longer than 50 characters")
    void shouldRejectNameWhenLongerThan50(String field) throws Exception {
        signIn("u1");
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field + "\":\"" + "a".repeat(51) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("should reject birthday when it is today or later in Taipei, or before 1900")
    void shouldRejectBirthdayWhenOutOfRange() throws Exception {
        signIn("u1");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        for (java.time.LocalDate day : List.of(today, today.plusDays(1), java.time.LocalDate.of(1899, 12, 31))) {
            mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"birthday\":\"" + day + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("common.validation_failed"))
                    .andExpect(jsonPath("$.message").value("birthdayInRange: Birthday must be between 1900-01-01 and yesterday"));
        }
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("should reject birthday when it is not a yyyy-MM-dd date")
    void shouldRejectBirthdayWhenMalformed() throws Exception {
        signIn("u1");
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthday\":\"12/04/1995\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.bad_request"));
        verifyNoInteractions(userService);
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

    @ParameterizedTest
    @ValueSource(strings = {"search", "account", "explore", "login", "notifications", "poll", "sanity", "api", "admin",
            "settings"})
    @DisplayName("should reject a reserved username on profile update")
    void shouldRejectProfileUpdateWhenUsernameReserved(String username) throws Exception {
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("should accept usernames that only contain a reserved name")
    void shouldAcceptUsernameWhenOnlyContainingReservedName() {
        assertThat(List.of("admin_1", "searcher", "my_polls", "poll_fan")).allMatch(u -> u.matches(ReservedUsernames.USERNAME_PATTERN));
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

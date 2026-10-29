package com.vomatt.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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

import com.vomatt.auth.PasskeyService;
import com.vomatt.auth.dto.PasskeyResponse;
import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.FollowUserDto;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.dto.VoteResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController")
class UserControllerTest {

    @Mock UserService userService;
    @Mock VoteService voteService;
    @Mock PasskeyService passkeyService;
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
                new UserPrincipal(userId, List.of("user"), null), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    @DisplayName("should accept every bot kind as avatarUrl when updating profile")
    void shouldAcceptAvatarUrlWhenBotKindKnown() throws Exception {
        signIn("u1");
        when(userService.updateMyProfile(eq("u1"), any())).thenAnswer(inv -> new UserProfileResponse("id-1", "alice", null, null,
                inv.<com.vomatt.users.dto.UpdateProfileRequest>getArgument(1).getAvatarUrl(), null, 0, 0, 0, 0,
                null, null, null, null, null, false, false, false, null, null));

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
        when(userService.updateMyProfile(eq("u1"), any())).thenAnswer(inv -> new UserProfileResponse("id-1", "alice", null, null,
                inv.<com.vomatt.users.dto.UpdateProfileRequest>getArgument(1).getAvatarUrl(), null, 0, 0, 0, 0,
                null, null, null, null, null, false, false, false, null, null));

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
                .thenReturn(new UserProfileResponse("id-1", "alice", "Al", null, "bot:boba", null, 0, 0, 0, 0,
                        null, null, null, null, null, false, false, false, null, null));

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
                .thenReturn(new UserProfileResponse("id-1", "alice", null, null, "bot:boba", null, 0, 0, 0, 0,
                        "Alice", "Chen", null, null, null, false, false, false, null, null));

        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alice\",\"lastName\":\"Chen\",\"birthday\":\"1995-04-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Alice"))
                // The public profile never carries the birthday; GET /api/users/me does
                .andExpect(jsonPath("$.data.birthday").doesNotExist());
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

    private static UserProfileResponse profile(Boolean isFollowing, Boolean isBlocked) {
        return new UserProfileResponse("id-1", "alice", null, null, "bot:boba", null, 0, 0, 0, 0,
                null, null, null, null, null, isFollowing, isFollowing, isBlocked, null, null);
    }

    @Test
    @DisplayName("should return null relation flags under isFollowing / isBlocked keys when signed out")
    void shouldReturnNullRelationFlagsWhenSignedOut() throws Exception {
        when(userService.getUserProfile("alice", null)).thenReturn(profile(null, null));

        mockMvc.perform(get("/api/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("id-1"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasKey("isFollowing")))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasKey("isBlocked")))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasKey("followsYou")))
                .andExpect(jsonPath("$.data.isFollowing").isEmpty())
                .andExpect(jsonPath("$.data.following").doesNotExist())
                .andExpect(jsonPath("$.data.blocked").doesNotExist());
    }

    @Test
    @DisplayName("should treat the anonymous token of a permitAll path as signed out")
    void shouldPassNullViewerWhenAnonymousAuthentication() throws Exception {
        // What AnonymousAuthenticationFilter leaves on a public path when there is no valid token
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        when(userService.getUserProfile("alice", null)).thenReturn(profile(null, null));

        mockMvc.perform(get("/api/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFollowing").isEmpty());
    }

    @Test
    @DisplayName("should pass the signed-in viewer to the profile lookup")
    void shouldPassViewerIdWhenSignedIn() throws Exception {
        signIn("u1");
        when(userService.getUserProfile("alice", "u1")).thenReturn(profile(true, false));

        mockMvc.perform(get("/api/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isFollowing").value(true))
                .andExpect(jsonPath("$.data.isBlocked").value(false));
    }

    @Test
    @DisplayName("should pass cursor, limit and caller through when listing followers and following")
    void shouldPassCursorWhenListingFollowersAndFollowing() throws Exception {
        signIn("u1");
        FollowUserDto bob = new FollowUserDto("id-2", "bob", "Bob", null, false, true);
        when(userService.listFollowers("alice", "c1", 10, "u1")).thenReturn(new CursorResponse<>(List.of(bob), "next"));
        when(userService.listFollowing("alice", null, null, "u1")).thenReturn(new CursorResponse<>(List.of(), null));

        mockMvc.perform(get("/api/users/alice/followers").param("cursor", "c1").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].username").value("bob"))
                .andExpect(jsonPath("$.data.items[0].isFollowing").value(false))
                .andExpect(jsonPath("$.data.items[0].followsYou").value(true))
                .andExpect(jsonPath("$.data.items[0].following").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value("next"));
        mockMvc.perform(get("/api/users/alice/following"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("should require sign-in for follower lists, /api/users/me/** and /api/users/search while the profile stays public")
    void shouldRequireSignInWhenReadingFollowLists() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        java.util.function.BiPredicate<String[], String> matches = (patterns, path) -> Arrays.stream(patterns)
                .anyMatch(p -> parser.parse(p).matches(PathContainer.parsePath(path)));
        assertThat(matches.test(SecurityEndpoints.PUBLIC_GET, "/api/users/alice")).isTrue();
        assertThat(matches.test(SecurityEndpoints.AUTHENTICATED_USERS, "/api/users/alice")).isFalse();
        assertThat(matches.test(SecurityEndpoints.AUTHENTICATED_USERS, "/api/users/alice/votes")).isFalse();
        // AUTHENTICATED_USERS is matched before PUBLIC_GET, so these require sign-in
        for (String path : List.of("/api/users/alice/followers", "/api/users/alice/following", "/api/users/alice/mutual-followers", "/api/users/me",
                "/api/users/me/follows", "/api/users/search", "/api/users/search/followers")) {
            assertThat(matches.test(SecurityEndpoints.AUTHENTICATED_USERS, path)).as(path).isTrue();
        }
    }

    @Test
    @DisplayName("should return mutual follower count and preview under their keys, null when signed out")
    void shouldReturnMutualFollowerKeysWhenReadingProfile() throws Exception {
        signIn("u1");
        when(userService.getUserProfile("alice", "u1")).thenReturn(new UserProfileResponse("id-1", "alice", null, null,
                "bot:boba", null, 0, 0, 1204, 0, null, null, null, null, null, true, false, false,
                3, List.of(new UserDto("id-2", "jtan", "J Tan", "bot:boba"))));

        mockMvc.perform(get("/api/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mutualFollowersCount").value(3))
                .andExpect(jsonPath("$.data.mutualFollowersPreview", hasSize(1)))
                .andExpect(jsonPath("$.data.mutualFollowersPreview[0].id").value("id-2"))
                .andExpect(jsonPath("$.data.mutualFollowersPreview[0].username").value("jtan"))
                .andExpect(jsonPath("$.data.mutualFollowersPreview[0].displayName").value("J Tan"))
                .andExpect(jsonPath("$.data.mutualFollowersPreview[0].avatarUrl").value("bot:boba"));

        SecurityContextHolder.clearContext();
        when(userService.getUserProfile("alice", null)).thenReturn(profile(null, null));
        mockMvc.perform(get("/api/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasKey("mutualFollowersCount")))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasKey("mutualFollowersPreview")))
                .andExpect(jsonPath("$.data.mutualFollowersCount").isEmpty())
                .andExpect(jsonPath("$.data.mutualFollowersPreview").isEmpty());
    }

    @Test
    @DisplayName("should pass cursor, limit and caller through when listing mutual followers")
    void shouldPassCursorWhenListingMutualFollowers() throws Exception {
        signIn("u1");
        FollowUserDto jtan = new FollowUserDto("id-2", "jtan", "J Tan", "bot:boba", true, false);
        when(userService.listMutualFollowers("alice", "c1", 10, "u1")).thenReturn(new CursorResponse<>(List.of(jtan), "next"));

        mockMvc.perform(get("/api/users/alice/mutual-followers").param("cursor", "c1").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].username").value("jtan"))
                .andExpect(jsonPath("$.data.items[0].isFollowing").value(true))
                .andExpect(jsonPath("$.data.items[0].followsYou").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value("next"));
    }

    @Test
    @DisplayName("should wrap the caller's passkeys in ApiResponse when listing")
    void shouldReturnPasskeysWhenListing() throws Exception {
        signIn("u1");
        UUID id = UUID.randomUUID();
        when(passkeyService.list("u1")).thenReturn(List.of(
                new PasskeyResponse(id, "iPhone", java.time.OffsetDateTime.now(), null, List.of("hybrid", "internal"))));

        mockMvc.perform(get("/api/users/me/passkeys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].name").value("iPhone"))
                .andExpect(jsonPath("$.data[0].transports[1]").value("internal"));
    }

    @Test
    @DisplayName("should delete the passkey as the caller")
    void shouldDeletePasskeyWhenSignedIn() throws Exception {
        signIn("u1");
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/users/me/passkeys/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        verify(passkeyService).delete("u1", id);
    }

    @Test
    @DisplayName("should respond 404 when the passkey is not the caller's")
    void shouldReturnNotFoundWhenDeletingForeignPasskey() throws Exception {
        signIn("u1");
        UUID id = UUID.randomUUID();
        doThrow(ApiException.notFound(MessageKey.COMMON_NOT_FOUND)).when(passkeyService).delete("u1", id);

        mockMvc.perform(delete("/api/users/me/passkeys/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("common.not_found"));
    }

    @Test
    @DisplayName("should respond 400 without calling the service when the passkey id is not a UUID")
    void shouldRejectWhenPasskeyIdMalformed() throws Exception {
        signIn("u1");

        mockMvc.perform(delete("/api/users/me/passkeys/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.type_mismatch"));
        verifyNoInteractions(passkeyService);
    }
}

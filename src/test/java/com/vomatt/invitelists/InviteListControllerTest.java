package com.vomatt.invitelists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.springframework.util.AntPathMatcher;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.invitelists.dto.InviteListMemberResponse;
import com.vomatt.invitelists.dto.InviteListMembersRequest;
import com.vomatt.invitelists.dto.InviteListRequest;
import com.vomatt.invitelists.dto.InviteListResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteListController")
class InviteListControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();
    private final String inviteListId = UUID.randomUUID().toString();

    @Mock InviteListService inviteListService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks InviteListController inviteListController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(inviteListController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        UserPrincipal principal = new UserPrincipal(USER_ID, List.of("user"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String ids(int count) {
        return Stream.generate(() -> "\"" + UUID.randomUUID() + "\"").limit(count).collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("should require sign-in on every /api/invite-lists path: no public pattern matches, so anyRequest().authenticated() applies")
    void shouldRequireAuthenticationWhenCallingInviteLists() {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String path : List.of("/api/invite-lists", "/api/invite-lists/" + inviteListId, "/api/invite-lists/" + inviteListId + "/members",
                "/api/invite-lists/" + inviteListId + "/members/" + UUID.randomUUID())) {
            assertThat(Stream.of(SecurityEndpoints.PUBLIC_GET, SecurityEndpoints.PUBLIC_AUTH, SecurityEndpoints.PUBLIC_SWAGGER,
                    SecurityEndpoints.PUBLIC_ACTUATOR).flatMap(Arrays::stream).anyMatch(p -> matcher.match(p, path))).isFalse();
        }
    }

    @Test
    @DisplayName("should return 201 with the Invite List when one is created")
    void shouldReturn201WhenInviteListCreated() throws Exception {
        when(inviteListService.createInviteList(any(InviteListRequest.class), eq(USER_ID)))
                .thenReturn(new InviteListResponse(inviteListId, "Book club", 0, null));

        mockMvc.perform(post("/api/invite-lists").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Book club\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(inviteListId))
                .andExpect(jsonPath("$.data.memberCount").value(0));
    }

    @Test
    @DisplayName("should return 400 common.validation_failed when the name is blank or too long")
    void shouldReturn400WhenNameInvalid() throws Exception {
        for (String name : List.of("   ", "x".repeat(51))) {
            mockMvc.perform(put("/api/invite-lists/{inviteListId}", inviteListId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"" + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        }
        verify(inviteListService, never()).renameInviteList(any(), any(), any());
    }

    @Test
    @DisplayName("should return 400 common.validation_failed when members are empty, over 100 or an invalid address")
    void shouldReturn400WhenMembersInvalid() throws Exception {
        for (String body : List.of("{\"userIds\": []}", "{\"userIds\": [" + ids(101) + "]}", "{\"emails\": [\"not-an-email\"]}",
                "{\"userIds\": [null]}")) {
            mockMvc.perform(post("/api/invite-lists/{inviteListId}/members", inviteListId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        }
        verify(inviteListService, never()).addMembers(any(), any(), any());
    }

    @Test
    @DisplayName("should return 200 with members listed by address when the owner adds an email")
    void shouldReturn200WhenOwnerAddsMembers() throws Exception {
        when(inviteListService.addMembers(eq(inviteListId), any(InviteListMembersRequest.class), eq(USER_ID)))
                .thenReturn(List.of(new InviteListMemberResponse(null, null, null, "dave@example.com", null)));

        mockMvc.perform(post("/api/invite-lists/{inviteListId}/members", inviteListId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\" Dave@Example.com\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("dave@example.com"))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
        verify(inviteListService).addMembers(eq(inviteListId), any(InviteListMembersRequest.class), eq(USER_ID));
    }

    @Test
    @DisplayName("should return 404 invite_list.not_found when the Invite List is someone else's")
    void shouldReturn404WhenInviteListNotOwned() throws Exception {
        when(inviteListService.getMembers(inviteListId, USER_ID)).thenThrow(ApiException.notFound(MessageKey.INVITE_LIST_NOT_FOUND));

        mockMvc.perform(get("/api/invite-lists/{inviteListId}/members", inviteListId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.INVITE_LIST_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("should return the same 404 invite_list.not_found when the Invite List does not exist")
    void shouldReturn404WhenInviteListMissing() throws Exception {
        when(inviteListService.renameInviteList(eq(inviteListId), any(InviteListRequest.class), eq(USER_ID)))
                .thenThrow(ApiException.notFound(MessageKey.INVITE_LIST_NOT_FOUND));

        mockMvc.perform(put("/api/invite-lists/{inviteListId}", inviteListId).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.INVITE_LIST_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("should route removal by address to the email path and by ID to the user path")
    void shouldRouteMemberRemovalWhenEmailOrUserId() throws Exception {
        String memberId = UUID.randomUUID().toString();
        when(inviteListService.removeEmailMember(inviteListId, "dave@example.com", USER_ID)).thenReturn(List.of());
        when(inviteListService.removeMember(inviteListId, memberId, USER_ID)).thenReturn(List.of());

        mockMvc.perform(delete("/api/invite-lists/{inviteListId}/members", inviteListId).param("email", "dave@example.com"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/invite-lists/{inviteListId}/members/{userId}", inviteListId, memberId))
                .andExpect(status().isOk());
    }
}

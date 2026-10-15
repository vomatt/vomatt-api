package com.vomatt.groups;

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
import com.vomatt.groups.dto.GroupMemberResponse;
import com.vomatt.groups.dto.GroupMembersRequest;
import com.vomatt.groups.dto.GroupRequest;
import com.vomatt.groups.dto.GroupResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("GroupController")
class GroupControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();
    private final String groupId = UUID.randomUUID().toString();

    @Mock GroupService groupService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks GroupController groupController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(groupController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        UserPrincipal principal = new UserPrincipal(USER_ID, "test@test.com", List.of("user"));
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
    @DisplayName("should require sign-in on every /api/groups path: no public pattern matches, so anyRequest().authenticated() applies")
    void shouldRequireAuthenticationWhenCallingGroups() {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String path : List.of("/api/groups", "/api/groups/" + groupId, "/api/groups/" + groupId + "/members",
                "/api/groups/" + groupId + "/members/" + UUID.randomUUID())) {
            assertThat(Stream.of(SecurityEndpoints.PUBLIC_GET, SecurityEndpoints.PUBLIC_AUTH, SecurityEndpoints.PUBLIC_SWAGGER,
                    SecurityEndpoints.PUBLIC_ACTUATOR).flatMap(Arrays::stream).anyMatch(p -> matcher.match(p, path))).isFalse();
        }
    }

    @Test
    @DisplayName("should return 201 with the Group when one is created")
    void shouldReturn201WhenGroupCreated() throws Exception {
        when(groupService.createGroup(any(GroupRequest.class), eq(USER_ID)))
                .thenReturn(new GroupResponse(groupId, "Book club", 0, null));

        mockMvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Book club\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(groupId))
                .andExpect(jsonPath("$.data.memberCount").value(0));
    }

    @Test
    @DisplayName("should return 400 common.validation_failed when the name is blank or too long")
    void shouldReturn400WhenNameInvalid() throws Exception {
        for (String name : List.of("   ", "x".repeat(51))) {
            mockMvc.perform(put("/api/groups/{groupId}", groupId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"" + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        }
        verify(groupService, never()).renameGroup(any(), any(), any());
    }

    @Test
    @DisplayName("should return 400 common.validation_failed when members are empty, over 100 or an invalid address")
    void shouldReturn400WhenMembersInvalid() throws Exception {
        for (String body : List.of("{\"userIds\": []}", "{\"userIds\": [" + ids(101) + "]}", "{\"emails\": [\"not-an-email\"]}",
                "{\"userIds\": [null]}")) {
            mockMvc.perform(post("/api/groups/{groupId}/members", groupId).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        }
        verify(groupService, never()).addMembers(any(), any(), any());
    }

    @Test
    @DisplayName("should return 200 with members listed by address when the owner adds an email")
    void shouldReturn200WhenOwnerAddsMembers() throws Exception {
        when(groupService.addMembers(eq(groupId), any(GroupMembersRequest.class), eq(USER_ID)))
                .thenReturn(List.of(new GroupMemberResponse(null, null, null, "dave@example.com", null)));

        mockMvc.perform(post("/api/groups/{groupId}/members", groupId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\": [\" Dave@Example.com\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("dave@example.com"))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
        verify(groupService).addMembers(eq(groupId), any(GroupMembersRequest.class), eq(USER_ID));
    }

    @Test
    @DisplayName("should return 404 group.not_found when the Group is someone else's")
    void shouldReturn404WhenGroupNotOwned() throws Exception {
        when(groupService.getMembers(groupId, USER_ID)).thenThrow(ApiException.notFound(MessageKey.GROUP_NOT_FOUND));

        mockMvc.perform(get("/api/groups/{groupId}/members", groupId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.GROUP_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("should return the same 404 group.not_found when the Group does not exist")
    void shouldReturn404WhenGroupMissing() throws Exception {
        when(groupService.renameGroup(eq(groupId), any(GroupRequest.class), eq(USER_ID)))
                .thenThrow(ApiException.notFound(MessageKey.GROUP_NOT_FOUND));

        mockMvc.perform(put("/api/groups/{groupId}", groupId).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.GROUP_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("should route removal by address to the email path and by ID to the user path")
    void shouldRouteMemberRemovalWhenEmailOrUserId() throws Exception {
        String memberId = UUID.randomUUID().toString();
        when(groupService.removeEmailMember(groupId, "dave@example.com", USER_ID)).thenReturn(List.of());
        when(groupService.removeMember(groupId, memberId, USER_ID)).thenReturn(List.of());

        mockMvc.perform(delete("/api/groups/{groupId}/members", groupId).param("email", "dave@example.com"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/groups/{groupId}/members/{userId}", groupId, memberId))
                .andExpect(status().isOk());
    }
}

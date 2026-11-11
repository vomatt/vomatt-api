package com.vomatt.users;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.vomatt.users.dto.UpdateCommunityRequest;
import com.vomatt.users.dto.UserDto;

@ExtendWith(MockitoExtension.class)
class CommunityControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock CommunityService communityService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks CommunityController controller;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(USER_ID.toString(), List.of("user"), null), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldMakeCallerTheOwnerWhenUserCreatesCommunity() throws Exception {
        when(communityService.createCommunity("sunrise_tower", "Sunrise Tower Committee", USER_ID))
                .thenReturn(new UserDto("o1", "sunrise_tower", "Sunrise Tower Committee", null, null, null, true));

        mockMvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"sunrise_tower\", \"displayName\": \"Sunrise Tower Committee\" }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("sunrise_tower"))
                .andExpect(jsonPath("$.data.community").value(true));
    }

    @Test
    void shouldRejectReservedUsernameWhenUserCreatesCommunity() throws Exception {

        mockMvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"settings\", \"displayName\": \"Sunrise\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(communityService);
    }

    @Test
    void shouldIgnoreUsernameWhenUpdatingCommunityProfile() throws Exception {
        UUID communityId = UUID.randomUUID();

        mockMvc.perform(patch("/api/communities/" + communityId).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"stolen\", \"displayName\": \"Sunrise\", \"bio\": \"Hi\" }"))
                .andExpect(status().isOk());

        verify(communityService).updateProfile(eq(communityId), eq(new UpdateCommunityRequest("Sunrise", "Hi")), eq(USER_ID.toString()));
    }

    @Test
    void shouldRejectBlankDisplayNameWhenUpdatingCommunityProfile() throws Exception {
        mockMvc.perform(patch("/api/communities/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"displayName\": \"   \" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(communityService);
    }
}

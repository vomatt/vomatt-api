package com.vomatt.users;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.vomatt.users.dto.UserDto;

@ExtendWith(MockitoExtension.class)
class OfficialControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock OfficialService officialService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks OfficialController controller;

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
    void shouldMakeCallerTheOwnerWhenUserCreatesOfficial() throws Exception {
        when(officialService.createOfficial("sunrise_tower", "Sunrise Tower Committee", USER_ID))
                .thenReturn(new UserDto("o1", "sunrise_tower", "Sunrise Tower Committee", null, true));

        mockMvc.perform(post("/api/officials").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"sunrise_tower\", \"displayName\": \"Sunrise Tower Committee\" }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("sunrise_tower"))
                .andExpect(jsonPath("$.data.official").value(true));
    }

    @Test
    void shouldRejectReservedUsernameWhenUserCreatesOfficial() throws Exception {

        mockMvc.perform(post("/api/officials").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"settings\", \"displayName\": \"Sunrise\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(officialService);
    }
}

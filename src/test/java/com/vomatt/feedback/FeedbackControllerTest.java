package com.vomatt.feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController")
class FeedbackControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    @Mock FeedbackService feedbackService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks FeedbackController feedbackController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(feedbackController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(USER_ID, List.of("user"), null), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should return 201 with the new id when feedback is valid")
    void shouldReturn201WhenFeedbackValid() throws Exception {
        UUID id = UUID.randomUUID();
        when(feedbackService.createFeedback(eq(USER_ID), eq("Please add dark mode."))).thenReturn(id);

        mockMvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Please add dark mode.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    @DisplayName("should return 400 when content is blank or over 2000 characters")
    void shouldReturn400WhenContentBlankOrTooLong() throws Exception {
        for (String content : List.of("   ", "x".repeat(2001))) {
            mockMvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\": \"" + content + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(feedbackService);
    }
}

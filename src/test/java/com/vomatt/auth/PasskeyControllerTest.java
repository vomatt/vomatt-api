package com.vomatt.auth;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.security.UserPrincipal;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasskeyController")
class PasskeyControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    @Mock PasskeyService passkeyService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks PasskeyController passkeyController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(passkeyController)
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
    @DisplayName("should wrap the caller's registration options in ApiResponse")
    void shouldReturnOptionsWhenSignedIn() throws Exception {
        when(passkeyService.registrationOptions(USER_ID)).thenReturn(new PasskeyRegistrationOptionsResponse(
                new PasskeyRegistrationOptionsResponse.Rp("vomatt.com", "Vomatt"),
                new PasskeyRegistrationOptionsResponse.User("AZCosh", "user@example.com", "User"), "Qm8A",
                List.of(new PasskeyRegistrationOptionsResponse.PubKeyCredParam("public-key", -7)), 300_000, List.of(),
                new PasskeyRegistrationOptionsResponse.AuthenticatorSelection("required", true, "preferred"),
                List.of("client-device"), "none"));

        mockMvc.perform(post("/api/auth/passkey/register/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rp.id").value("vomatt.com"))
                .andExpect(jsonPath("$.data.challenge").value("Qm8A"))
                .andExpect(jsonPath("$.data.excludeCredentials").isEmpty());
    }
}

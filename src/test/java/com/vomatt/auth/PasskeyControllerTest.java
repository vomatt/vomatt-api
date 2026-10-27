package com.vomatt.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest;
import com.vomatt.auth.dto.PasskeyResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
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

    private static final String VERIFY_BODY = """
            {"credential": {"id": "RBBL", "rawId": "RBBL", "type": "public-key",
              "response": {"clientDataJSON": "eyJ0", "attestationObject": "o2Nm", "transports": ["internal"]},
              "authenticatorAttachment": "platform", "clientExtensionResults": {}}, "name": "iPhone"}""";

    @Test
    @DisplayName("should return the stored passkey when verification succeeds")
    void shouldReturnPasskeyWhenVerified() throws Exception {
        UUID id = UUID.randomUUID();
        when(passkeyService.verifyRegistration(eq(USER_ID), any(PasskeyRegistrationVerifyRequest.class)))
                .thenReturn(new PasskeyResponse(id, "iPhone", OffsetDateTime.now(), null));

        mockMvc.perform(post("/api/auth/passkey/register/verify").contentType(MediaType.APPLICATION_JSON).content(VERIFY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.name").value("iPhone"));
    }

    @Test
    @DisplayName("should answer 400 auth.passkey.invalid when the service rejects the passkey")
    void shouldReturn400WhenPasskeyInvalid() throws Exception {
        when(messageService.resolve(any(MessageKey.class))).thenReturn("Passkey verification failed. Please try again");
        when(passkeyService.verifyRegistration(eq(USER_ID), any(PasskeyRegistrationVerifyRequest.class)))
                .thenThrow(ApiException.badRequest(MessageKey.AUTH_PASSKEY_INVALID));

        mockMvc.perform(post("/api/auth/passkey/register/verify").contentType(MediaType.APPLICATION_JSON).content(VERIFY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("auth.passkey.invalid"));
    }

    @Test
    @DisplayName("should answer 400 validation_failed without calling the service when credential is missing or name too long")
    void shouldReturn400WhenBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/passkey/register/verify").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        mockMvc.perform(post("/api/auth/passkey/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(VERIFY_BODY.replace("\"iPhone\"", "\"" + "x".repeat(101) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_VALIDATION_FAILED.code()));
        verifyNoInteractions(passkeyService);
    }
}

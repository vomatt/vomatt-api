package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.auth.dto.SendOtpRequest;
import com.vomatt.auth.dto.VerifyOtpRequest;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    AuthService authService;
    @Mock
    LocalizedMessageService messageService;
    @InjectMocks
    AuthController authController;

    @BeforeEach
    void setUp() {
        // standaloneSetup: 不需要 Spring Boot context，不連接資料庫
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @Test
    void sendOtp_withValidEmail_shouldReturn200() throws Exception {
        doNothing().when(authService).sendOtp(anyString(), any(), any(), anyBoolean());

        mockMvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendOtpRequest("user@example.com", null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void verifyOtp_withValidCode_shouldReturnToken() throws Exception {
        AuthResponse mockResponse = new AuthResponse("mock-token", "mock-refresh-token",
                new AuthResponse.UserInfo("id1", "user@example.com", "test_0001", "Test", List.of("user"), null, null));
        when(authService.verifyOtp(anyString(), any(), anyString(), anyBoolean())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest("user@example.com", "123456", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.user.username").value("test_0001"));
    }

    @Test
    void verifyOtp_withWrongCode_shouldReturn401() throws Exception {
        when(messageService.resolve(any(MessageKey.class))).thenReturn("驗證碼錯誤或已過期");
        when(authService.verifyOtp(anyString(), any(), anyString(), anyBoolean())).thenThrow(
                ApiException.unauthorized(MessageKey.AUTH_INVALID_OTP));

        mockMvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest("user@example.com", "000000", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(MessageKey.AUTH_INVALID_OTP.code()));
    }
}

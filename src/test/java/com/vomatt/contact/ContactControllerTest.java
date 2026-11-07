package com.vomatt.contact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContactController")
class ContactControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock ContactService contactService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks ContactController contactController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(contactController)
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    private static String body(String name, String email, String message, String locale) {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("email", email);
        fields.put("message", message);
        fields.put("locale", locale);
        return JSON.writeValueAsString(fields);
    }

    @Test
    void shouldReturn201WithIdWhenMessageValid() throws Exception {
        UUID id = UUID.randomUUID();
        when(contactService.createMessage(eq("203.0.113.7"), argThat(r -> "zh".equals(r.getLocale())))).thenReturn(id);

        mockMvc.perform(post("/api/contact").contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                        .content(body("Alice", "alice@example.com", "Hello", "zh")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    void shouldReturn400WhenFieldInvalid() throws Exception {
        List<String> bodies = List.of(
                body("  ", "alice@example.com", "Hello", "en"),
                body("x".repeat(101), "alice@example.com", "Hello", "en"),
                body("Alice", "not-an-email", "Hello", "en"),
                body("Alice", "a".repeat(250) + "@example.com", "Hello", "en"),
                body("Alice", "alice@example.com", "  ", "en"),
                body("Alice", "alice@example.com", "x".repeat(5001), "en"),
                body("Alice", "alice@example.com", "Hello", "fr"),
                body("Alice", "alice@example.com", "Hello", "EN"),
                body("Alice", "alice@example.com", "Hello", null));
        for (String content : bodies) {
            mockMvc.perform(post("/api/contact").contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        }
        verifyNoInteractions(contactService);
    }

    @Test
    void shouldAcceptLimitsWhenFieldsAtMaxLength() throws Exception {
        when(contactService.createMessage(anyString(), any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/api/contact").contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(100), "alice@example.com", "x".repeat(5000), "en")))
                .andExpect(status().isCreated());
    }
}

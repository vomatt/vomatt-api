package com.vomatt.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;

class BillingControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BillingController())
            .setControllerAdvice(new GlobalExceptionHandler(org.mockito.Mockito.mock(LocalizedMessageService.class)))
            .build();

    @ParameterizedTest
    @ValueSource(strings = { "/api/billing/checkout", "/api/billing/portal", "/api/billing/webhook" })
    void shouldAnswerBillingUnavailableWhenBillingIsDisabled(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("billing.unavailable"));
    }
}

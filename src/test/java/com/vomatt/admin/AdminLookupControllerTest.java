package com.vomatt.admin;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.lookups.LookupService;

@ExtendWith(MockitoExtension.class)
class AdminLookupControllerTest {

    @Mock LookupService lookupService;
    @Mock LocalizedMessageService messageService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = AdminControllerTestSupport.mockMvc(new AdminLookupController(lookupService), messageService);
        AdminControllerTestSupport.authenticateAs("admin");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{ \"lookupType\": \"  \" }", "{ \"lookupKey\": \"\" }" })
    void shouldRejectUpdateWhenTypeOrKeyIsBlank(String body) throws Exception {
        mockMvc.perform(put("/api/admin/lookups/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d01")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("common.validation_failed"));
        verifyNoInteractions(lookupService);
    }
}

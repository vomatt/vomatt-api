package com.vomatt.common.exception;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies how {@link GlobalExceptionHandler} handles authorization-failure exceptions.
 * Uses standaloneSetup (no Spring context, no DB),
 * registers a test controller that throws authorization exceptions, and sets the handler as controllerAdvice.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    LocalizedMessageService messageService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Make i18n resolve return a non-null string so the test does not depend on the real properties
        lenient().when(messageService.resolve(any(MessageKey.class)))
                .thenReturn("You do not have permission to perform this action");

        GlobalExceptionHandler handler = new GlobalExceptionHandler(messageService);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void accessDeniedException_returns403() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_FORBIDDEN.code()));
    }

    @Test
    void authorizationDeniedException_returns403() throws Exception {
        // AuthorizationDeniedException is the subclass actually thrown when method security (@PreAuthorize) fails
        mockMvc.perform(get("/test/authorization-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void apiException_withoutData_returnsNullData_backwardCompatible() throws Exception {
        // Existing ApiException without data is unchanged: data is always null (same before and after the Phase 0 extension)
        mockMvc.perform(get("/test/api-exception-no-data"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void apiException_withData_returns422AndStructuredData() throws Exception {
        // Phase 0 error envelope extension: 422 + structured data (e.g. {distanceM: 384})
        mockMvc.perform(get("/test/api-exception-with-data"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.distanceM").value(384));
    }

    @Test
    void illegalArgumentException_returns400BadRequest() throws Exception {
        // IllegalArgumentException (invalid UUID path id, tag name that cannot produce a slug, etc.) should return 400, not 500
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_BAD_REQUEST.code()));
    }

    @Test
    void shouldReturn404WhenPathHasNoHandler() throws Exception {
        mockMvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_NOT_FOUND.code()));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/no-resource")
        public String noResource() throws Exception {
            throw new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/test/no-resource", "test/no-resource");
        }

        @GetMapping("/test/illegal-argument")
        public String illegalArgument() {
            throw new IllegalArgumentException("Invalid UUID string: not-a-uuid");
        }

        @GetMapping("/test/access-denied")
        public String accessDenied() {
            throw new AccessDeniedException("Access is denied");
        }

        @GetMapping("/test/authorization-denied")
        public String authorizationDenied() {
            throw new AuthorizationDeniedException("Access Denied", new DeniedResult());
        }

        @GetMapping("/test/api-exception-no-data")
        public String apiExceptionNoData() {
            throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);
        }

        @GetMapping("/test/api-exception-with-data")
        public String apiExceptionWithData() {
            throw ApiException.withData(HttpStatus.UNPROCESSABLE_ENTITY, MessageKey.VOTE_NOT_ALLOWED,
                    new OutOfRangeData(384));
        }
    }

    record OutOfRangeData(int distanceM) {
    }

    /** Minimal AuthorizationResult, used only to construct AuthorizationDeniedException */
    static class DeniedResult implements AuthorizationResult {
        @Override
        public boolean isGranted() {
            return false;
        }
    }
}

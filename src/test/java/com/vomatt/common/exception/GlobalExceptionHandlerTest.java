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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 {@link GlobalExceptionHandler} 對授權失敗例外的處理。
 * 使用 standaloneSetup（不啟動 Spring context、不連 DB），
 * 註冊一個會丟授權例外的測試 controller，並將 handler 設為 controllerAdvice。
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    LocalizedMessageService messageService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 讓 i18n resolve 回傳非 null 字串，避免測試依賴實際 properties
        lenient().when(messageService.resolve(any(MessageKey.class)))
                .thenReturn("沒有權限執行此操作");

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
        // AuthorizationDeniedException 是 method-security（@PreAuthorize）失敗時實際丟出的子類
        mockMvc.perform(get("/test/authorization-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void apiException_withoutData_returnsNullData_backwardCompatible() throws Exception {
        // 既有無 data 的 ApiException 行為不變：data 恆為 null（Phase 0 擴充前後一致）
        mockMvc.perform(get("/test/api-exception-no-data"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void apiException_withData_returns422AndStructuredData() throws Exception {
        // Phase 0 錯誤 envelope 擴充：422 + 結構化 data（例：{distanceM: 384}）
        mockMvc.perform(get("/test/api-exception-with-data"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.distanceM").value(384));
    }

    @Test
    void illegalArgumentException_returns400BadRequest() throws Exception {
        // 無效 UUID path id、無法產生 slug 的標籤名稱等 IllegalArgumentException 應回 400，而非 500
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(MessageKey.COMMON_BAD_REQUEST.code()));
    }

    @RestController
    static class TestController {

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

    /** 最小化的 AuthorizationResult，僅供建構 AuthorizationDeniedException 使用 */
    static class DeniedResult implements AuthorizationResult {
        @Override
        public boolean isGranted() {
            return false;
        }
    }
}

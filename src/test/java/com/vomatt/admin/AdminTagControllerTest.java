package com.vomatt.admin;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.tags.TagService;
import com.vomatt.tags.dto.CreateTagRequest;
import com.vomatt.tags.dto.TagDto;
import com.vomatt.tags.dto.UpdateTagRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standaloneSetup + 以 {@link AuthorizationManagerBeforeMethodInterceptor#preAuthorize()} 包裝 controller，
 * 讓類別層級 {@code @PreAuthorize("hasRole('ADMIN')")} 在不啟動 Spring context 的情況下實際生效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminTagController")
class AdminTagControllerTest {

    @Mock TagService tagService;
    @Mock LocalizedMessageService messageService;

    MockMvc mockMvc;
    ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        ProxyFactory proxyFactory = new ProxyFactory(new AdminTagController(tagService));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());

        mockMvc = MockMvcBuilders.standaloneSetup(proxyFactory.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** JwtAuthFilter 將 roles claim 映射為 ROLE_<UPPER> authorities */
    private static void authenticateAs(String role) {
        UserPrincipal principal = new UserPrincipal(role + "-id", role + "@test.com", List.of(role));
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null,
                AuthorityUtils.createAuthorityList("ROLE_" + role.toUpperCase()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 未帶 token 的請求在 filter chain 中為 anonymous authentication */
    private static void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    private TagDto buildTagDto(String id, String name) {
        TagDto dto = new TagDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSlug(name.toLowerCase());
        dto.setUsageCount(0);
        return dto;
    }

    private String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("controller 類別層級須掛 @PreAuthorize(hasRole('ADMIN'))，且路徑受 AUTHENTICATED_ADMIN 保護")
    void classLevelPreAuthorizeAndAdminPath() {
        PreAuthorize preAuthorize = AdminTagController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
        assertThat(SecurityEndpoints.AUTHENTICATED_ADMIN).contains("/api/admin/**");
    }

    // ─── POST /api/admin/tags ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/admin/tags")
    class CreateTagTests {

        @Test
        @DisplayName("ADMIN role should be able to create tag and return 200")
        void shouldReturn200WhenAdmin() throws Exception {
            authenticateAs("admin");
            TagDto created = buildTagDto("new-id", "Science");
            when(tagService.createTag(any(CreateTagRequest.class))).thenReturn(created);

            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/admin/tags")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Science"));
        }

        @Test
        @DisplayName("USER 角色應被拒絕並回傳 403")
        void shouldReturn403WhenUser() throws Exception {
            authenticateAs("user");
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/admin/tags")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));

            verify(tagService, never()).createTag(any());
        }

        @Test
        @DisplayName("未認證（anonymous）請求應被拒絕並回傳 403")
        void shouldReturn403WhenUnauthenticated() throws Exception {
            anonymous();
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/admin/tags")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isForbidden());

            verify(tagService, never()).createTag(any());
        }

        @Test
        @DisplayName("Should return 400 when request body validation fails")
        void shouldReturn400WhenInvalidRequest() throws Exception {
            authenticateAs("admin");
            CreateTagRequest request = new CreateTagRequest();
            // name is empty, should trigger @NotBlank validation

            mockMvc.perform(post("/api/admin/tags")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isBadRequest());

            verify(tagService, never()).createTag(any());
        }
    }

    // ─── PUT /api/admin/tags/{tagId} ──────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/admin/tags/{tagId}")
    class UpdateTagTests {

        @Test
        @DisplayName("ADMIN role should be able to update tag and return 200")
        void shouldReturn200WhenAdmin() throws Exception {
            authenticateAs("admin");
            String tagId = "existing-tag-id";
            TagDto updated = buildTagDto(tagId, "Updated Science");
            when(tagService.updateTag(eq(tagId), any(UpdateTagRequest.class))).thenReturn(updated);

            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("Updated Science");

            mockMvc.perform(put("/api/admin/tags/{tagId}", tagId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(tagId));
        }

        @Test
        @DisplayName("USER role should be rejected and return 403")
        void shouldReturn403WhenUser() throws Exception {
            authenticateAs("user");
            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("Updated Science");

            mockMvc.perform(put("/api/admin/tags/{tagId}", "some-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isForbidden());

            verify(tagService, never()).updateTag(anyString(), any());
        }

        @Test
        @DisplayName("Should return 400 when request body validation fails")
        void shouldReturn400WhenInvalidRequest() throws Exception {
            authenticateAs("admin");
            UpdateTagRequest request = new UpdateTagRequest();
            // name is empty, should trigger @NotBlank validation

            mockMvc.perform(put("/api/admin/tags/{tagId}", "some-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── DELETE /api/admin/tags/{tagId} ───────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/admin/tags/{tagId}")
    class DeleteTagTests {

        @Test
        @DisplayName("ADMIN role should be able to delete tag and return 200")
        void shouldReturn200WhenAdmin() throws Exception {
            authenticateAs("admin");
            String tagId = "tag-to-delete";
            doNothing().when(tagService).deleteTag(tagId);

            mockMvc.perform(delete("/api/admin/tags/{tagId}", tagId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.success").value(true));

            verify(tagService).deleteTag(tagId);
        }

        @Test
        @DisplayName("USER role should be rejected and return 403")
        void shouldReturn403WhenUser() throws Exception {
            authenticateAs("user");
            mockMvc.perform(delete("/api/admin/tags/{tagId}", "some-id"))
                    .andExpect(status().isForbidden());

            verify(tagService, never()).deleteTag(anyString());
        }

        @Test
        @DisplayName("Unauthenticated (anonymous) request should be rejected and return 403")
        void shouldReturn403WhenUnauthenticated() throws Exception {
            anonymous();
            mockMvc.perform(delete("/api/admin/tags/{tagId}", "some-id"))
                    .andExpect(status().isForbidden());

            verify(tagService, never()).deleteTag(anyString());
        }
    }
}

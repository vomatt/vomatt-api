package com.vomattapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vomattapi.application.controller.AdminTagController;
import com.vomattapi.application.dto.tag.CreateTagRequest;
import com.vomattapi.application.dto.tag.UpdateTagRequest;
import com.vomattapi.application.dto.tag.TagDto;
import com.vomattapi.application.service.tag.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import com.vomattapi.infrastructure.security.services.UserDetailsImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminTagController.class)
@ActiveProfiles("test")
@Import(AdminTagControllerTest.TestSecurityConfig.class)
@DisplayName("AdminTagController")
class AdminTagControllerTest {

    /** 測試用簡化安全設定：CSRF 停用，所有路徑開放（由 @PreAuthorize 控制存取） */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    private static UserDetailsImpl adminUser() {
        return new UserDetailsImpl("admin-id", "admin", "admin@test.com", "pw",
                true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static UserDetailsImpl regularUser() {
        return new UserDetailsImpl("user-id", "user", "user@test.com", "pw",
                true, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean TagService tagService;
    // AuthTokenFilter 依賴的元件
    @MockBean com.vomattapi.infrastructure.security.jwt.JwtUtils jwtUtils;
    @MockBean com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl userDetailsService;
    @MockBean com.vomattapi.application.service.auth.JwtBlacklistService jwtBlacklistService;

    private TagDto buildTagDto(String id, String name) {
        TagDto dto = new TagDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSlug(name.toLowerCase());
        dto.setUsageCount(0);
        return dto;
    }

    // ─── POST /api/v1/admin/tags ──────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/admin/tags")
    class CreateTagTests {

        @Test
        @DisplayName("ADMIN 角色應能建立標籤並回傳 200")
        void shouldReturn200WhenAdmin() throws Exception {
            TagDto created = buildTagDto("new-id", "Science");
            when(tagService.createTag(any(CreateTagRequest.class))).thenReturn(created);

            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/v1/admin/tags")
                    .with(csrf())
                    .with(user(adminUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("標籤建立成功"))
                    .andExpect(jsonPath("$.data.name").value("Science"));
        }

        @Test
        @DisplayName("USER 角色應被拒絕並回傳 403")
        void shouldReturn403WhenUser() throws Exception {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/v1/admin/tags")
                    .with(csrf())
                    .with(user(regularUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("未認證請求應被拒絕並回傳 403")
        void shouldReturn403WhenUnauthenticated() throws Exception {
            CreateTagRequest request = new CreateTagRequest();
            request.setName("Science");

            mockMvc.perform(post("/api/v1/admin/tags")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("請求體驗證失敗時應回傳 400")
        void shouldReturn400WhenInvalidRequest() throws Exception {
            CreateTagRequest request = new CreateTagRequest();
            // name 為空，應觸發 @NotBlank 驗證

            mockMvc.perform(post("/api/v1/admin/tags")
                    .with(csrf())
                    .with(user(adminUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── PUT /api/v1/admin/tags/{tagId} ───────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/admin/tags/{tagId}")
    class UpdateTagTests {

        @Test
        @DisplayName("ADMIN 角色應能更新標籤並回傳 200")
        void shouldReturn200WhenAdmin() throws Exception {
            String tagId = "existing-tag-id";
            TagDto updated = buildTagDto(tagId, "Updated Science");
            when(tagService.updateTag(eq(tagId), any(UpdateTagRequest.class))).thenReturn(updated);

            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("Updated Science");

            mockMvc.perform(put("/api/v1/admin/tags/{tagId}", tagId)
                    .with(csrf())
                    .with(user(adminUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("標籤更新成功"))
                    .andExpect(jsonPath("$.data.id").value(tagId));
        }

        @Test
        @DisplayName("USER 角色應被拒絕並回傳 403")
        void shouldReturn403WhenUser() throws Exception {
            UpdateTagRequest request = new UpdateTagRequest();
            request.setName("Updated Science");

            mockMvc.perform(put("/api/v1/admin/tags/{tagId}", "some-id")
                    .with(csrf())
                    .with(user(regularUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("請求體驗證失敗時應回傳 400")
        void shouldReturn400WhenInvalidRequest() throws Exception {
            UpdateTagRequest request = new UpdateTagRequest();
            // name 為空，應觸發 @NotBlank 驗證

            mockMvc.perform(put("/api/v1/admin/tags/{tagId}", "some-id")
                    .with(csrf())
                    .with(user(adminUser()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── DELETE /api/v1/admin/tags/{tagId} ────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/admin/tags/{tagId}")
    class DeleteTagTests {

        @Test
        @DisplayName("ADMIN 角色應能刪除標籤並回傳 200")
        void shouldReturn200WhenAdmin() throws Exception {
            String tagId = "tag-to-delete";
            doNothing().when(tagService).deleteTag(tagId);

            mockMvc.perform(delete("/api/v1/admin/tags/{tagId}", tagId)
                    .with(csrf())
                    .with(user(adminUser())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("標籤刪除成功"));
        }

        @Test
        @DisplayName("USER 角色應被拒絕並回傳 403")
        void shouldReturn403WhenUser() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/tags/{tagId}", "some-id")
                    .with(csrf())
                    .with(user(regularUser())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("未認證請求應被拒絕並回傳 403")
        void shouldReturn403WhenUnauthenticated() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/tags/{tagId}", "some-id")
                    .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}

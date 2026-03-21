package com.vomattapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vomattapi.application.controller.TagController;
import com.vomattapi.application.dto.response.TagDto;
import com.vomattapi.application.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TagController.class)
@ActiveProfiles("test")
@Import(TagControllerTest.TestSecurityConfig.class)
@DisplayName("TagController")
class TagControllerTest {

    /** 測試用簡化安全設定：CSRF 停用，所有路徑開放（公開端點不需認證） */
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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean TagService tagService;
    // AuthTokenFilter 依賴的元件
    @MockBean com.vomattapi.application.security.jwt.JwtUtils jwtUtils;
    @MockBean com.vomattapi.application.security.services.UserDetailsServiceImpl userDetailsService;
    @MockBean com.vomattapi.application.service.JwtBlacklistService jwtBlacklistService;

    private TagDto buildTagDto(String id, String name) {
        TagDto dto = new TagDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSlug(name.toLowerCase());
        dto.setUsageCount(10);
        return dto;
    }

    // ─── GET /api/v1/tags ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tags")
    class GetAllTagsTests {

        @Test
        @DisplayName("應該回傳 200 和所有標籤列表")
        void shouldReturn200WithTagList() throws Exception {
            TagDto tag1 = buildTagDto("id-1", "Science");
            TagDto tag2 = buildTagDto("id-2", "Politics");
            when(tagService.getAllTags()).thenReturn(List.of(tag1, tag2));

            mockMvc.perform(get("/api/v1/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value("id-1"))
                    .andExpect(jsonPath("$.data[1].id").value("id-2"));
        }

        @Test
        @DisplayName("無標籤時應回傳空列表")
        void shouldReturnEmptyListWhenNoTags() throws Exception {
            when(tagService.getAllTags()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/v1/tags/popular ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tags/popular")
    class GetPopularTagsTests {

        @Test
        @DisplayName("應該回傳 200 和分頁熱門標籤")
        void shouldReturn200WithPopularTagsPage() throws Exception {
            TagDto tag = buildTagDto("id-1", "Trending");
            Page<TagDto> page = new PageImpl<>(List.of(tag));
            when(tagService.getPopularTags(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/tags/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].id").value("id-1"));
        }

        @Test
        @DisplayName("無熱門標籤時應回傳空分頁")
        void shouldReturnEmptyPageWhenNoPopularTags() throws Exception {
            when(tagService.getPopularTags(any(Pageable.class))).thenReturn(Page.empty());

            mockMvc.perform(get("/api/v1/tags/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }
}

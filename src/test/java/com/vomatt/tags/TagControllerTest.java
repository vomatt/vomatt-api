package com.vomatt.tags;

import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.tags.dto.TagDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("TagController")
class TagControllerTest {

    @Mock TagService tagService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks TagController tagController;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(tagController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
    }

    private TagDto buildTagDto(String id, String name) {
        TagDto dto = new TagDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSlug(name.toLowerCase());
        dto.setUsageCount(10);
        return dto;
    }

    // ─── GET /api/tags ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/tags")
    class GetAllTagsTests {

        @Test
        @DisplayName("Should return 200 and list of all tags")
        void shouldReturn200WithTagList() throws Exception {
            TagDto tag1 = buildTagDto("id-1", "Science");
            TagDto tag2 = buildTagDto("id-2", "Politics");
            when(tagService.getAllTags()).thenReturn(List.of(tag1, tag2));

            mockMvc.perform(get("/api/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value("id-1"))
                    .andExpect(jsonPath("$.data[1].id").value("id-2"));
        }

        @Test
        @DisplayName("Should return empty list when there are no tags")
        void shouldReturnEmptyListWhenNoTags() throws Exception {
            when(tagService.getAllTags()).thenReturn(List.of());

            mockMvc.perform(get("/api/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/tags/popular ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/tags/popular")
    class GetPopularTagsTests {

        @Test
        @DisplayName("Should return 200 and a cursor page of popular tags")
        void shouldReturn200WithPopularTagsPage() throws Exception {
            TagDto tag = buildTagDto("id-1", "Trending");
            when(tagService.getPopularTags(null, null)).thenReturn(new CursorResponse<>(List.of(tag), "next"));

            mockMvc.perform(get("/api/tags/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value("id-1"))
                    .andExpect(jsonPath("$.data.nextCursor").value("next"));
        }

        @Test
        @DisplayName("Should pass cursor and limit through")
        void shouldPassCursorAndLimit() throws Exception {
            when(tagService.getPopularTags("c1", 5)).thenReturn(new CursorResponse<>(List.of(), null));

            mockMvc.perform(get("/api/tags/popular").param("cursor", "c1").param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isEmpty());
        }
    }
}

package com.vomatt.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "Offset-style page container (page number and total count); most list endpoints use CursorResponse instead")
public record PageResponse<T>(
        @Schema(description = "Items of this page") List<T> content,
        @Schema(description = "Total number of items across all pages", example = "123") long total,
        @Schema(description = "Current page number (1-based)", example = "1") int page,
        @Schema(description = "Page size", example = "20") int limit
) {

    public static <T> PageResponse<T> of(List<T> content, long total, int page, int limit) {
        return new PageResponse<>(content, total, page, limit);
    }

    // 由 Spring Data Page 轉成 PageResponse；page 採 1-based。
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getNumber() + 1, page.getSize());
    }

    // 由 Spring Data Page + 轉換器（entity → dto）建構。
    public static <S, T> PageResponse<T> from(Page<S> page, java.util.function.Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }
}

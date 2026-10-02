package com.vomatt.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "分頁回應容器")
public record PageResponse<T>(
        @Schema(description = "本頁資料") List<T> content,
        @Schema(description = "資料總筆數", example = "123") long total,
        @Schema(description = "目前頁碼（1-based）", example = "1") int page,
        @Schema(description = "每頁筆數", example = "20") int limit
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

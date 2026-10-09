package com.vomatt.common.response;

import java.util.List;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Cursor-paged list container; pass `nextCursor` back as `cursor` to fetch the next page")
public record CursorResponse<T>(
        @Schema(description = "Items of this page") List<T> items,
        @Schema(description = "Opaque cursor of the next page; null when this is the last page", example = "MDE5OWMzYTItN2I1ZS03YzFkLTlhNGYtM2UyYjFkNWM2ZjcwfDQy", nullable = true) String nextCursor
) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    /**
     * Builds a page from rows fetched with {@code limit + 1}: the extra row only signals that another page exists.
     *
     * @param mapPage converts the whole page at once, so callers can batch-load per-page data
     */
    public static <E, T> CursorResponse<T> of(List<E> rows, int limit, Function<E, Cursor> cursorOf,
                                              Function<List<E>, List<T>> mapPage) {
        boolean hasMore = rows.size() > limit;
        List<E> page = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore ? cursorOf.apply(page.getLast()).encode() : null;
        return new CursorResponse<>(mapPage.apply(page), next);
    }

    /** Requested page size clamped to [1, MAX_LIMIT]; null means DEFAULT_LIMIT. */
    public static int limit(Integer requested) {
        return requested == null ? DEFAULT_LIMIT : Math.clamp(requested, 1, MAX_LIMIT);
    }
}

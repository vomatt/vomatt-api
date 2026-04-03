package com.vomattapi.application.dto.user;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 更新欄位顯示設定請求
 * key 為欄位名稱（email, firstName, lastName, location, points, membershipLevel）
 * value 為 true（顯示）或 false（隱藏）
 */
public record UpdateVisibilityRequest(
        @NotNull Map<String, Boolean> visibility
) {}

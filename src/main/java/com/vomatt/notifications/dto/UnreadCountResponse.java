package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "未讀的「Poll 結束」通知數")
public record UnreadCountResponse(@Schema(description = "未讀數", example = "3") long count) {
}

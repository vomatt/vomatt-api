package com.vomatt.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** toString() is what the audit log stores in details. */
@Schema(description = "Why the account is suspended; kept in the audit log")
public record SuspendUserRequest(
        @NotBlank @Size(max = 500)
        @Schema(description = "Reason, 1-500 characters", example = "Repeated spam in comments") String reason
) {
}

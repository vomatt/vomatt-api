package com.vomatt.admin.dto;

import com.vomatt.common.audit.AuditDetails;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** The reason is what the audit log stores in details; it is not stored anywhere else. */
@Schema(description = "Optional note on why the account is suspended. Audit-only: written to the audit log entry's `details`, "
        + "not stored on the account and not shown to the user")
public record SuspendUserRequest(
        @Size(max = 500)
        @Schema(description = "Reason, up to 500 characters", example = "Repeated spam in comments", nullable = true) String reason
) implements AuditDetails {

    @Override
    public String auditDetails() {
        return reason;
    }
}

package com.vomatt.admin.dto;

import com.vomatt.common.audit.AuditDetails;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** The audit log stores the new value in details ("pro=true" / "pro=false"). */
@Schema(description = "Whether the Poll is a Pro poll")
public record SetProRequest(
        @NotNull
        @Schema(description = "true turns Pro on, false turns it off", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean pro
) implements AuditDetails {

    @Override
    public String auditDetails() {
        return "pro=" + pro;
    }
}

package com.vomatt.admin.dto;

import com.vomatt.entity.Plan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The plan to put the account on")
public record ChangePlanRequest(
        @NotNull
        @Schema(description = "FREE or PRO", example = "PRO") Plan plan
) {}

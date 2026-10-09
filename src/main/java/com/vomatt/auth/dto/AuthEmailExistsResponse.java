package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of the email registration check")
public record AuthEmailExistsResponse(

        @Schema(description = "True if a user with this email is registered", example = "true")
        boolean exists
) {
}

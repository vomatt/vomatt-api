package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to check whether an email is registered; lets the client choose between login and signup wording.
 */
@Schema(description = "Request to check whether an email is registered")
public record CheckEmailRequest(

        @Schema(description = "Email address to check", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "user@example.com")
        @NotBlank @Email String email
) {
}

package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/** Request to send an email OTP (consumer login or admin console login). */
@Schema(description = "Request to send an email OTP")
public record SendOtpRequest(

        @Schema(description = "Email address; the server lower-cases and trims it, and stores a Gmail address in canonical form (no +tag or dots, domain gmail.com). Required in practice: a missing or blank value yields 400 auth.identifier.required", example = "user@example.com")
        @Email String email
) {
}

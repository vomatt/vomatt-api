package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to verify an email OTP and complete login / signup.
 */
@Schema(description = "Request to verify an email OTP")
public record VerifyOtpRequest(

        @Schema(description = "Email address the OTP was sent to. Required in practice: a missing or blank value yields 400 auth.identifier.required", example = "user@example.com")
        @Email String email,

        @Schema(description = "OTP code received by email", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code,

        @Schema(description = "Set to \"true\" to restrict to existing admin accounts (admin console login). Any other value, or null, means no restriction",
                example = "false", allowableValues = {"true", "false"}, nullable = true)
        String checkRole
) {
}

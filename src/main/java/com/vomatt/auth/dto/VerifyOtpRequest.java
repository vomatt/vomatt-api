package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request to verify an email OTP and complete login / signup (consumer login or admin console login). */
@Schema(description = "Request to verify an email OTP")
public record VerifyOtpRequest(

        @Schema(description = "Email address the OTP was sent to. Required in practice: a missing or blank value yields 400 auth.identifier.required", example = "user@example.com")
        @Email String email,

        @Schema(description = "OTP code received by email", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code
) {
}

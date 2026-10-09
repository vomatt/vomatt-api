package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/**
 * Request to send an email OTP.
 *
 * <p>{@code checkRole} is a string boolean ("true" / "false"); when "true" only admin accounts may get an OTP (admin console login).</p>
 */
@Schema(description = "Request to send an email OTP")
public record SendOtpRequest(

        @Schema(description = "Email address; the server lower-cases and trims it. Required in practice: a missing or blank value yields 400 auth.identifier.required", example = "user@example.com")
        @Email String email,

        @Schema(description = "Set to \"true\" to restrict to admin accounts (admin console login). Any other value, or null, means no restriction",
                example = "false", allowableValues = {"true", "false"}, nullable = true)
        String checkRole
) {
}

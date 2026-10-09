package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to send a phone OTP by SMS.
 *
 * <p>{@code type} defaults to {@code login}; use {@code signup} for registration (the user is created at verification).</p>
 */
@Schema(description = "Request to send a phone OTP by SMS")
public record PhoneSendOtpRequest(

        @Schema(description = "Phone number", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "OTP purpose. When null, treated as \"login\", which requires the phone number to be registered already. \"signup\" is for new numbers",
                example = "login", allowableValues = {"login", "signup"}, nullable = true)
        String type
) {
}

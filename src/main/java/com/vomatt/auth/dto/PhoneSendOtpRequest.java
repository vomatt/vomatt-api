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

        @Schema(description = "Phone number: a Taiwan mobile number (09xxxxxxxx) or E.164 (+ country code and number). Spaces, '-', '(' and ')' are ignored; stored and matched as E.164", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "OTP purpose, informational only: either value sends the same OTP, and the account is created at verification when the number is new. Null is treated as \"login\"",
                example = "login", allowableValues = {"login", "signup"}, nullable = true)
        String type
) {
}

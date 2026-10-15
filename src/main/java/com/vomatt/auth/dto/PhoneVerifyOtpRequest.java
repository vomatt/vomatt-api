package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to verify a phone OTP and complete login / signup.
 */
@Schema(description = "Request to verify a phone OTP")
public record PhoneVerifyOtpRequest(

        @Schema(description = "Phone number: a Taiwan mobile number (09xxxxxxxx) or E.164 (+ country code and number). Spaces, '-', '(' and ')' are ignored; stored and matched as E.164", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "OTP code received by SMS", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code
) {
}

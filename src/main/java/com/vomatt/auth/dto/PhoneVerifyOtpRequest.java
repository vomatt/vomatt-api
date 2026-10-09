package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to verify a phone OTP and complete login / signup.
 */
@Schema(description = "Request to verify a phone OTP")
public record PhoneVerifyOtpRequest(

        @Schema(description = "Phone number", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0912345678")
        @NotBlank String phone,

        @Schema(description = "OTP code received by SMS", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123456")
        @NotBlank String code
) {
}

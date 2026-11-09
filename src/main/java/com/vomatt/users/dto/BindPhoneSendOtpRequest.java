package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to send an SMS OTP for binding a phone number to the caller's account")
public record BindPhoneSendOtpRequest(
        @Schema(description = "Phone number: a Taiwan mobile number (09xxxxxxxx) or E.164 (+ country code and number). Spaces, '-', '(' and ')' are ignored; stored and matched as E.164",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "0912345678")
        @NotBlank String phone
) {
}

package com.vomattapi.application.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SigninRequest {
    @NotBlank
    private String email;

    @NotBlank
    private String verificationCode;
}

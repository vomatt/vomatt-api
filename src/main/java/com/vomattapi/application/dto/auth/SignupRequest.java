package com.vomattapi.application.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SignupRequest {
    @NotBlank
    @Size(min = 3, max = 20)
    private String username;

    @NotBlank
    @Size(max = 50)
    @Email
    private String email;

    @Size(max = 20)
    private String phoneNumber;

    @NotBlank
    @Size(min = 6, max = 6)
    private String verificationCode;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;
}

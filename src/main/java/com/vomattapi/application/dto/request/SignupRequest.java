package com.vomattapi.application.dto.request;

import java.util.Set;

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

    private Set<String> roles;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    public String getUsername()           { return username; }
    public String getEmail()              { return email; }
    public String getPhoneNumber()        { return phoneNumber; }
    public String getVerificationCode()   { return verificationCode; }
    public Set<String> getRoles()         { return roles; }
    public String getFirstName()          { return firstName; }
    public String getLastName()           { return lastName; }
}
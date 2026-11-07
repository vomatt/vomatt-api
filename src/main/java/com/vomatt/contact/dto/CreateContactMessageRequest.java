package com.vomatt.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateContactMessageRequest {

    @Schema(description = "Sender's name, not blank, max 100 characters", example = "Alice Chen")
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    private String name;

    @Schema(description = "Reply-to email address, max 254 characters; stored trimmed, case kept", example = "alice@example.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email is not valid")
    @Size(max = 254, message = "Email cannot exceed 254 characters")
    private String email;

    @Schema(description = "The question, not blank, max 5000 characters", example = "Can my school use Vomatt for student council elections?")
    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message cannot exceed 5000 characters")
    private String message;

    @Schema(description = "Language of the page the visitor wrote from", allowableValues = {"en", "zh"}, example = "en")
    @NotNull(message = "Locale is required")
    @Pattern(regexp = "en|zh", message = "Locale must be en or zh")
    private String locale;
}

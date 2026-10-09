package com.vomatt.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "User search result item; not masked by the owner's visibility settings")
public class UserDto {

    @Schema(description = "User id (UUIDv7)", example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
    private String id;
    @Schema(description = "Unique username", example = "alice")
    private String username;
    @Schema(description = "Email; null if the account signed up with phone only", example = "alice@example.com", nullable = true)
    private String email;
    @Schema(description = "Phone number; null if the account signed up with email only", example = "+886912345678", nullable = true)
    private String phoneNumber;
    @Schema(description = "First name; null if not set", example = "Alice", nullable = true)
    private String firstName;
    @Schema(description = "Last name; null if not set", example = "Chen", nullable = true)
    private String lastName;
    @Schema(description = "Location; null if not set", example = "Taipei", nullable = true)
    private String location;
    @Schema(description = "Points", example = "120")
    private int points;
    @Schema(description = "Membership level; null if not set", example = "basic", nullable = true)
    private String membershipLevel;
    @Schema(description = "False when the account is suspended", example = "true")
    private boolean active;
    @Schema(description = "When the account was created", example = "2026-08-01T10:00:00+08:00")
    private OffsetDateTime createdAt;
    @Schema(description = "Last sign-in time; null if the user never signed in", example = "2026-10-09T09:15:00+08:00", nullable = true)
    private OffsetDateTime lastLoginAt;
}

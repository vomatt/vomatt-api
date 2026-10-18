package com.vomatt.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response after a successful login, signup or token refresh: access token, refresh token and basic user info.
 *
 * <p>Access token is valid for 15 minutes and refresh token for 30 days by default (configurable by environment variables).</p>
 */
@Schema(description = "Response after a successful login, signup or token refresh")
public record AuthResponse(

        @Schema(description = "JWT access token; send it as `Authorization: Bearer <token>`. Valid for 15 minutes by default",
                example = "eyJhbGciOiJIUzI1NiJ9...")
        String token,

        @Schema(description = "Opaque refresh token, valid for 30 days by default. Use it with POST /api/auth/refresh when the access token expires. Single-use: every refresh returns a new one that replaces it",
                example = "8c7e3f4a-1234-5678-90ab-cdef12345678")
        String refreshToken,

        @Schema(description = "Basic user info. Null on POST /api/auth/refresh (keep the info from login); always present on login and signup",
                nullable = true)
        UserInfo user
) {

    /**
     * Basic user info included in a login response.
     */
    @Schema(description = "Basic user info included in a login response")
    public record UserInfo(

            @Schema(description = "User ID (UUIDv7)",
                    example = "0190a8b2-1234-7890-abcd-ef0123456789")
            String id,

            @Schema(description = "Email address. Null for users who signed up by phone OTP and have no email; email, Google, LINE and Apple users always have one",
                    example = "user@example.com", nullable = true)
            String email,

            @Schema(description = "Username used in the public profile path; generated at signup", example = "john_0427")
            String username,

            @Schema(description = "Display name. Set at signup (email local part, provider name, or the phone number); null only if the column is empty", example = "Ming Wang", nullable = true)
            String displayName,

            @Schema(description = "Role list",
                    example = "[\"user\"]",
                    allowableValues = {"user", "moderator", "admin"})
            List<String> roles,

            @Schema(description = "Avatar as `bot:<kind>` (the bot cast), assigned at random on sign-up. Older accounts may hold an image URL or null", example = "bot:boba",
                    nullable = true)
            String avatarUrl,

            @Schema(description = "Phone number in E.164. Null for users who signed up by email or OAuth and never added a phone", example = "+886912345678", nullable = true)
            String phoneNumber
    ) {
    }
}

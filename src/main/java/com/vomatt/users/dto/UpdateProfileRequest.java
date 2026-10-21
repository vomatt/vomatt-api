package com.vomatt.users.dto;

import com.vomatt.common.constant.ReservedUsernames;
import com.vomatt.users.BotAvatar; 
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Profile update; omitted or null fields are left unchanged")
public class UpdateProfileRequest {

    @Schema(description = "New username, used in the public profile path: 3-50 characters of [a-z0-9_], not a reserved name "
            + "(search, account, explore, login, notifications, poll, sanity, api, me, my, admin, settings); "
            + "null or omitted keeps the current value", example = "alice_c", minLength = 3, maxLength = 50, nullable = true)
    @Pattern(regexp = ReservedUsernames.USERNAME_PATTERN,
            message = "Username must be 3-50 characters of lowercase letters, digits or underscore, and not a reserved name")
    private String username;

    @Schema(description = "New display name (max 100 characters); null or omitted keeps the current value",
            example = "Alice C.", maxLength = 100, nullable = true)
    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    private String displayName;

    @Schema(description = "New first name (max 50 characters); null or omitted keeps the current value",
            example = "Alice", maxLength = 50, nullable = true)
    @Size(max = 50, message = "First name cannot exceed 50 characters")
    private String firstName;

    @Schema(description = "New last name (max 50 characters); null or omitted keeps the current value",
            example = "Chen", maxLength = 50, nullable = true)
    @Size(max = 50, message = "Last name cannot exceed 50 characters")
    private String lastName;

    @Schema(description = "New birthday as an ISO date (yyyy-MM-dd), strictly before today; null or omitted keeps the current value. "
            + "Cannot be cleared once set", example = "1995-04-12", nullable = true)
    @Past(message = "Birthday must be in the past")
    private LocalDate birthday;

    @Schema(description = "New bio (max 500 characters); null or omitted keeps the current value",
            example = "Poll enthusiast", maxLength = 500, nullable = true)
    @Size(max = 500, message = "Bio cannot exceed 500 characters")
    private String bio;

    @Schema(description = "New avatar, one of the bot cast as `bot:<kind>`; any other value is rejected. "
            + "null or omitted keeps the current value", example = "bot:boba", nullable = true)
    @Pattern(regexp = BotAvatar.PATTERN, message = "Avatar must be bot:<kind> with a known bot kind")
    private String avatarUrl;

    @Schema(description = "Whether Ballots are Anonymous when `POST /api/votes/{voteId}/vote` omits `anonymous`; "
            + "existing Ballots are unchanged. null or omitted keeps the current value", example = "true", nullable = true)
    private Boolean anonymousBallotByDefault;
}

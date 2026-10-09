package com.vomatt.users;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.users.dto.MyProfileResponse;
import com.vomatt.users.dto.UpdateProfileRequest;
import com.vomatt.users.dto.UpdateVisibilityRequest;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import com.vomatt.common.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vomatt.users.UserService;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.dto.VoteResponse;
import jakarta.validation.Valid;
import com.vomatt.common.audit.Auditable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import com.vomatt.common.config.OpenAPIConfig;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User management APIs")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final VoteService voteService;

    @GetMapping("/me")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","email":"alice@example.com","phoneNumber":null,"firstName":"Alice","lastName":"Chen","displayName":"Alice","bio":"Poll enthusiast","location":"Taipei","points":120,"membershipLevel":"basic","active":true,"lastLoginAt":"2026-10-09T09:15:00+08:00","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"visibilitySettings":{"email":false,"firstName":false,"lastName":false,"location":false,"points":false,"displayName":true,"bio":true,"membershipLevel":false}},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Get my profile", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: returns the caller's own profile with every field, ignoring visibility settings. \
            Unlike the public profile, nothing is masked; `visibilitySettings` reports what other users see \
            on `GET /api/users/{username}` (unset fields use their defaults: `displayName` and `bio` public, the rest hidden). \
            `totalPolls` counts Polls the user created that have opened (Open and Ended; not Scheduled or Cancelled before opening), \
            `totalVotes` counts Polls the user holds a Ballot in.
            **Side effects**: none
            **Errors**:
            - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        MyProfileResponse profile = userService.getMyProfile(principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PatchMapping("/me/visibility")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"email":false,"firstName":true,"lastName":true,"location":false,"points":false,"displayName":true,"bio":true,"membershipLevel":false},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Update profile visibility", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: sets which fields other users can see on the public profile. Send only the fields to change; \
            omitted fields keep their current setting and unknown keys are ignored. Returns the complete \
            settings map after the update (one boolean per controllable field).
            **Side effects**: persists the settings; takes effect immediately on `GET /api/users/{username}`
            **Errors**:
            - 400 `common.validation_failed`: `visibility` is missing
            - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> updateVisibility(
            @Valid @RequestBody UpdateVisibilityRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Boolean> result = userService.updateVisibility(principal.userId(), request.visibility());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{username}")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"username":"alice","displayName":"Alice","bio":"Poll enthusiast","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"email":null,"firstName":"Alice","lastName":null,"location":null,"points":null,"membershipLevel":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with this username (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Get user public profile", description = """
            **Auth**: public (the response is the same whether or not the caller is signed in)
            **Precondition**: none
            **Behavior**: returns the public view of a user. `username`, `joinedAt`, `totalPolls` and `totalVotes` are always present. \
            Every optional field is `null` unless the owner made it visible: `displayName` and `bio` are visible by default, \
            `email`, `firstName`, `lastName`, `location`, `points` and `membershipLevel` are hidden by default. \
            `totalPolls` counts only Polls that have opened (Open and Ended), matching `GET /api/users/{username}/votes`.
            **Side effects**: none
            **Errors**:
            - 404 `user.not_found`: no user has this username, or the user is suspended""")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(
            @Parameter(description = "Username of the user to look up", required = true, example = "alice")
            @PathVariable String username) {
        UserProfileResponse profile = userService.getUserProfile(username, false);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @GetMapping("/{username}/votes")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(name = "Page", value = OpenAPIConfig.EX_PAGE_OF_POLLS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No active user with this username (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Get a user's Polls", description = """
            **Auth**: public (optional login fills the viewer-specific fields of each Poll, e.g. the caller's Ballot)
            **Precondition**: the user exists and is not suspended
            **Behavior**: the Polls the user created that have opened (Open and Ended), most recently opened first, \
            cursor-paged (`limit` 1-50, default 20; out-of-range values are clamped). Scheduled Polls and Polls \
            Cancelled before opening are left out, so the count matches `totalPolls` on the public profile. \
            `nextCursor` is `null` on the last page.
            **Side effects**: none
            **Errors**:
            - 404 `user.not_found`: no user has this username, or the user is suspended
            - 400 `common.cursor_invalid`: `cursor` was not issued by this endpoint""")
    public ResponseEntity<ApiResponse<CursorResponse<VoteResponse>>> getUserPolls(
            @Parameter(description = "Username of the Poll creator", required = true, example = "alice") @PathVariable String username,
            @Parameter(description = "`nextCursor` from the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1-50 (default 20); out-of-range values are clamped", example = "20") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(voteService.getUserPolls(username, cursor, limit,
                principal != null ? principal.userId() : null)));
    }

    @PatchMapping("/me")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"username":"alice","displayName":"Alice C.","bio":"Poll enthusiast","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"email":null,"firstName":"Alice","lastName":null,"location":null,"points":null,"membershipLevel":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Update my profile", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: updates `displayName` and/or `bio`. A field that is omitted or `null` is left unchanged \
            (`null` cannot be used to clear a value; send an empty string instead). Returns the public profile after the update, \
            so the optional fields are masked by the caller's own visibility settings.
            **Side effects**: persists the new values
            **Errors**:
            - 400 `common.validation_failed`: `displayName` exceeds 100 characters or `bio` exceeds 500 characters
            - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        UserProfileResponse profile = userService.updateMyProfile(principal.userId(), request);
        log.info("Profile updated for user: {}", principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @GetMapping("/search")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"items":[{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","email":"alice@example.com","phoneNumber":null,"firstName":"Alice","lastName":"Chen","location":"Taipei","points":120,"membershipLevel":"basic","active":true,"createdAt":"2026-08-01T10:00:00+08:00","lastLoginAt":"2026-10-09T09:15:00+08:00"}],"nextCursor":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Search users by username", description = """
            **Auth**: required
            **Precondition**: `username` is not blank
            **Behavior**: case-insensitive partial match on username, ordered by username, cursor-paged \
            (`limit` 1-50, default 20; out-of-range values are clamped). `nextCursor` is `null` on the last page. \
            Items are not masked by the owners' visibility settings.
            **Side effects**: none
            **Errors**:
            - 400 `common.missing_param`: `username` is missing or blank""")
    public ResponseEntity<ApiResponse<CursorResponse<UserDto>>> searchUsers(
            @Parameter(description = "Username fragment to search for", required = true, example = "ali")
            @RequestParam String username,
            @Parameter(description = "`nextCursor` returned by the previous page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit) {
        if (username.isBlank()) {
            throw ApiException.badRequest(MessageKey.COMMON_MISSING_PARAM, "username");
        }
        CursorResponse<UserDto> users = userService.searchUsersByUsername(username.trim(), cursor, limit);
        log.info("User search completed for username: {}, page size {}", username, users.items().size());
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @DeleteMapping("/{userId}")
    @Auditable(action = "DELETE", resourceType = "USER", resourceIdIndex = 0)
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account does not exist (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Delete user account", description = """
            **Auth**: required (own account; an admin may delete any account)
            **Precondition**: `userId` is the caller's own id, or the caller has the admin role
            **Behavior**: permanently deletes the account. The user's Ballots are removed and released from the stored option counts \
            (this is a Retraction of every Ballot the user held).
            **Side effects**: all refresh tokens of the user are revoked, so the client must discard its tokens and sign out; \
            the deletion is written to the audit log
            **Errors**:
            - 403 `user.delete.forbidden`: deleting another user's account without the admin role
            - 404 `user.not_found`: no account with this id
            - 400 `common.bad_request`: `userId` is not a valid id (admin callers only; other callers get 403 first)""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteUser(
            @Parameter(description = "Id of the account to delete", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {

        userService.deleteUser(userId, principal);

        log.info("User {} deleted by {}", userId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

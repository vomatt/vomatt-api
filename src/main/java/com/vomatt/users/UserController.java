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
                    {"success":true,"data":{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","email":"alice@example.com","phoneNumber":null,"firstName":"Alice","lastName":"Chen","birthday":"1995-04-12","displayName":"Alice","bio":"Poll enthusiast","avatarUrl":"bot:boba","location":"Taipei","points":120,"membershipLevel":"basic","active":true,"lastLoginAt":"2026-10-09T09:15:00+08:00","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"visibilitySettings":{"firstName":false,"lastName":false,"location":false,"points":false,"displayName":true,"bio":true,"membershipLevel":false},"anonymousBallotByDefault":false},"message":null,"errorCode":null,"error":null}""")))
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
            `totalVotes` counts Public, non-Deleted Polls the user holds a Ballot in. \
            `anonymousBallotByDefault` is whether Ballots are Anonymous when a Ballot does not say otherwise (false until set).
            **Side effects**: none
            **Errors**:
            - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        MyProfileResponse profile = userService.getMyProfile(principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @GetMapping("/me/export")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"exportedAt":"2026-10-23T10:00:00+08:00","profile":{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","email":"alice@example.com","phoneNumber":null,"firstName":"Alice","lastName":"Chen","birthday":"1995-04-12","displayName":"Alice","bio":"Poll enthusiast","avatarUrl":"bot:boba","location":"Taipei","points":120,"membershipLevel":"basic","active":true,"lastLoginAt":"2026-10-09T09:15:00+08:00","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":1,"totalVotes":1,"visibilitySettings":{"firstName":false,"lastName":false,"location":true,"points":false,"displayName":true,"bio":true,"membershipLevel":false},"anonymousBallotByDefault":false},"preferences":{"visibility.location":"true"},"polls":[{"id":"0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05","title":"Lunch?","description":null,"audience":"PUBLIC","startTime":"2026-10-01T12:00:00+08:00","endTime":"2026-10-08T12:00:00+08:00","createdAt":"2026-10-01T12:00:00+08:00","deleted":false,"options":["Ramen","Sushi"]}],"comments":[{"id":"0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b","voteId":"0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05","text":"Ramen!","createdAt":"2026-10-02T09:00:00+08:00"}],"ballots":[{"voteId":"0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05","voteTitle":"Lunch?","optionId":"0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813","optionText":"Ramen","anonymous":false,"castAt":"2026-10-02T08:30:00+08:00"}]},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Already exported today (`common.rate_limited`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Too many requests, please try again later","errorCode":"common.rate_limited","error":"Too many requests, please try again later"}""")))
    @Operation(summary = "Export my data", description = """
            **Auth**: required
            **Precondition**: not exported yet today (UTC)
            **Behavior**: returns everything stored about the caller in one JSON document: the profile (as `GET /api/users/me`), stored preferences, \
            Polls the caller created (Deleted included, with option texts), Comments written (not deleted) and Ballots cast (with the chosen option; \
            an Anonymous Ballot of an Ended Poll has no option once sealed). Nothing about other users is included.
            **Side effects**: none; counted toward the one export per UTC day
            **Errors**:
            - 404 `user.not_found`: the account no longer exists
            - 429 `common.rate_limited`: an export was already produced today""")
    public ResponseEntity<ApiResponse<com.vomatt.users.dto.UserDataExportResponse>> exportMyData(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.exportMyData(principal.userId())));
    }

    @PatchMapping("/me/visibility")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"firstName":true,"lastName":true,"location":false,"points":false,"displayName":true,"bio":true,"membershipLevel":false},"message":null,"errorCode":null,"error":null}""")))
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
                    {"success":true,"data":{"username":"alice","displayName":"Alice","bio":"Poll enthusiast","avatarUrl":"bot:boba","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"firstName":"Alice","lastName":null,"location":null,"points":null,"membershipLevel":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with this username (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Get user public profile", description = """
            **Auth**: public (the response is the same whether or not the caller is signed in)
            **Precondition**: none
            **Behavior**: returns the public view of a user. `username`, `avatarUrl`, `joinedAt`, `totalPolls` and `totalVotes` (Public, non-Deleted Polls only) are always present \
            (`avatarUrl` is `null` when never set). \
            Every optional field is `null` unless the owner made it visible: `displayName` and `bio` are visible by default, \
            `firstName`, `lastName`, `location`, `points` and `membershipLevel` are hidden by default; email and birthday are never returned. \
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
            **Behavior**: the PUBLIC Polls the user created that have opened (Open and Ended), most recently opened first, \
            cursor-paged (`limit` 1-50, default 20; out-of-range values are clamped). Scheduled Polls and Polls \
            Cancelled before opening are left out, so the count matches `totalPolls` on the public profile. \
            `nextCursor` is `null` on the last page.
            **Side effects**: none
            **Errors**:
            - 404 `user.not_found`: no user has this username, or the user is suspended
            - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list or another ordering of this list""")
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
                    {"success":true,"data":{"username":"alice","displayName":"Alice C.","bio":"Poll enthusiast","avatarUrl":"bot:boba","joinedAt":"2026-08-01T10:00:00+08:00","totalPolls":4,"totalVotes":17,"firstName":"Alice","lastName":null,"location":null,"points":null,"membershipLevel":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Username already used by another account (errorCode `user.username.taken`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Username already taken","errorCode":"user.username.taken","error":"Username already taken"}""")))
    @Operation(summary = "Update my profile", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: updates `username`, `displayName`, `firstName`, `lastName`, `birthday`, `bio`, `avatarUrl` and/or `anonymousBallotByDefault` (the default for Ballots that omit `anonymous`; existing Ballots are unchanged). `avatarUrl` must be `bot:<kind>` with one of the bot cast kinds, or `bot:none` for no character. `firstName` and `lastName` are stored without surrounding whitespace, and a blank value clears them. `birthday` is an ISO date (`yyyy-MM-dd`) from 1900-01-01 to yesterday in Asia/Taipei. Sending the current `username` is a no-op, except that an older account holding a reserved name gets 400 (omit `username` when unchanged). A field that is omitted or `null` is left unchanged \
            (`null` cannot be used to clear a value; send an empty string instead, except `avatarUrl` and `birthday`, which cannot be cleared). Returns the public profile after the update, \
            so the optional fields are masked by the caller's own visibility settings.
            **Side effects**: persists the new values; after a `username` change the old public profile path returns 404
            **Errors**:
            - 400 `common.validation_failed`: `username` is not 3-50 characters of [a-z0-9_] or is a reserved name (see the `username` schema), \
            `displayName` exceeds 100 characters, `firstName` or `lastName` exceeds 50 characters, `birthday` is before 1900-01-01 or not before today (Asia/Taipei), `bio` exceeds 500 characters, or `avatarUrl` is not a known `bot:<kind>` or `bot:none`
            - 400 `common.bad_request`: `birthday` is not a valid `yyyy-MM-dd` date
            - 404 `user.not_found`: the account no longer exists
            - 409 `user.username.taken`: another account already uses `username`""")
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
                    {"success":true,"data":{"items":[{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","displayName":"Alice","avatarUrl":"bot:boba"},{"id":"0199c1a2-8c4f-7e5b-a021-3d6f9b2c4e58","username":"alicia","displayName":null,"avatarUrl":null}],"nextCursor":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Search users by username", description = """
            **Auth**: required
            **Precondition**: `username` is not blank
            **Behavior**: case-insensitive partial match on username, ordered by username, cursor-paged \
            (`limit` 1-50, default 20; out-of-range values are clamped). `nextCursor` is `null` on the last page. \
            Each item carries only public profile fields (`id`, `username`, `displayName`, `avatarUrl`); \
            `displayName` is `null` when the owner hid it on the public profile. Email, phone number and other private fields are never returned.
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

    @GetMapping("/me/blocks")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of blocked users",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"items":[{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"alice","displayName":"Alice","avatarUrl":"bot:boba"}],"nextCursor":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "List blocked users", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: users the caller blocked, most recently blocked first, cursor-paged \
            (`limit` 1-50, default 20; out-of-range values are clamped). `nextCursor` is `null` on the last page. \
            Each item carries only public profile fields, like user search.
            **Side effects**: none
            **Errors**:
            - 400 `common.cursor_invalid`: `cursor` is malformed or was issued by another list""")
    public ResponseEntity<ApiResponse<CursorResponse<UserDto>>> listBlockedUsers(
            @Parameter(description = "`nextCursor` returned by the previous page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size (1-50, default 20)") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.listBlockedUsers(principal.userId(), cursor, limit)));
    }

    @PostMapping("/me/blocks/{userId}")
    @Auditable(action = "BLOCK", resourceType = "USER", resourceIdIndex = 0)
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Blocking yourself (errorCode `user.block.self`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"You cannot block yourself","errorCode":"user.block.self","error":"You cannot block yourself"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account does not exist (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @Operation(summary = "Block a user", description = """
            **Auth**: required
            **Precondition**: `userId` is another existing account
            **Behavior**: one-way block. From then on the caller no longer sees, in `GET /api/votes` (Feed / Explore / Search / Top), \
            Polls the blocked user created, nor, in `GET /api/votes/{voteId}/comments` and its replies, Comment threads the blocked user \
            started or Replies they wrote. Nothing else changes: the Poll detail, voter list, profile and My Polls still show them, \
            and the blocked user is not told and sees the caller's content as before. Idempotent: blocking an already blocked user succeeds.
            **Side effects**: the block is written to the audit log
            **Errors**:
            - 400 `user.block.self`: `userId` is the caller's own id
            - 400 `common.bad_request`: `userId` is not a valid id
            - 404 `user.not_found`: no account with this id
            - 409 `common.conflict`: the same block was added concurrently; it is in place, nothing to retry""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> blockUser(
            @Parameter(description = "Id of the user to block", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        userService.blockUser(principal.userId(), userId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @DeleteMapping("/me/blocks/{userId}")
    @Auditable(action = "UNBLOCK", resourceType = "USER", resourceIdIndex = 0)
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Unblock a user", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: removes the caller's block on the user, so their Polls and Comments show again. \
            Idempotent: succeeds when the user was not blocked or does not exist.
            **Side effects**: the unblock is written to the audit log
            **Errors**:
            - 400 `common.bad_request`: `userId` is not a valid id""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unblockUser(
            @Parameter(description = "Id of the user to unblock", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        userService.unblockUser(principal.userId(), userId);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
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
            **Auth**: required (own account; an active admin signed in through the admin console may delete any account)
            **Precondition**: `userId` is the caller's own id, or the caller holds an admin console token (`POST /api/auth/admin/verify-otp`) and is an active admin in the database
            **Behavior**: permanently deletes the account. The user's Ballots are removed and released from the stored option counts \
            (this is a Retraction of every Ballot the user held).
            **Side effects**: once the deletion commits, all refresh tokens of the user are revoked, so the client must discard its tokens and sign out; \
            the deletion is written to the audit log
            **Errors**:
            - 403 `user.delete.forbidden`: deleting another user's account without an admin console token, or without being an active admin in the database
            - 409 `admin.last_admin`: the account is the only active admin
            - 409 `common.conflict`: another admin change is in progress; retry
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

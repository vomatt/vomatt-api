package com.vomatt.users;

import static com.vomatt.common.config.OpenAPIConfig.ERR_CODE;
import static com.vomatt.common.config.OpenAPIConfig.ERR_END;
import static com.vomatt.common.config.OpenAPIConfig.ERR_ERROR;
import static com.vomatt.common.config.OpenAPIConfig.ERR_MESSAGE;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.StorageUnavailableResponse;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.MyCommunityResponse;
import com.vomatt.users.dto.NewCommunityRequest;
import com.vomatt.users.dto.CommunityMemberRequest;
import com.vomatt.users.dto.CommunityMemberResponse;
import com.vomatt.users.dto.CommunityOwnerRequest;
import com.vomatt.users.dto.CommunityRoleRequest;
import com.vomatt.users.dto.UpdateCommunityRequest;
import com.vomatt.users.dto.UserDto;
import com.vomatt.users.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Community account members. Authorized by the caller's own membership (the real member, whether or not the request
 * also sends {@code X-Act-As}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Community Account", description = "Community accounts (a group's page) and the members who manage them")
public class CommunityController {

    private final CommunityService communityService;

    private static final String EX_MEMBER = """
            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "username": "alice", "displayName": "Alice", "avatarUrl": "bot:boba",
              "role": "OWNER", "joinedAt": "2026-11-09T10:00:00+08:00" }""";
    private static final String EX_MEMBER_RESPONSE = "{ \"success\": true, \"data\": " + EX_MEMBER
            + ", \"message\": null, \"errorCode\": null, \"error\": null }";
    private static final String EX_MEMBERS_RESPONSE = "{ \"success\": true, \"data\": [" + EX_MEMBER
            + "], \"message\": null, \"errorCode\": null, \"error\": null }";
    private static final String EX_OK = """
            { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
              "message": null, "errorCode": null, "error": null }
            """;

    private static final String EX_PROFILE_RESPONSE = """
            { "success": true, "data": { "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                "displayName": "Sunrise Tower Committee", "bio": "Residents' committee of Sunrise Tower",
                "avatarUrl": "/api/avatars/019a5e3c-2f1d-7b6a-8c4e-5d7f9a1b3c2e.jpg", "joinedAt": "2026-11-09T10:00:00+08:00",
                "totalPolls": 3, "totalVotes": 0, "followersCount": 120, "followingCount": 0, "firstName": null, "lastName": null,
                "location": null, "gender": null, "isFollowing": false, "followsYou": false, "isBlocked": false, "community": true },
              "message": null, "errorCode": null, "error": null }
            """;

    private static final String ROLES = """
            Roles: OWNER and ADMIN manage members; an ADMIN never changes or removes an OWNER nor grants OWNER. \
            CHIEF_EDITOR, EDITOR and ANALYST cannot manage members.""";
    private static final String NOT_MEMBER = "- 403 `community.not_member`: the caller is not a member of this community account, or it does not exist or is suspended\n";
    private static final String ROLE_FORBIDDEN = "- 403 `community.role_forbidden`: the caller's role does not allow this change\n";
    private static final String MEMBER_NOT_FOUND = "- 404 `community.member.not_found`: the user is not a member\n";
    private static final String LAST_OWNER = "- 409 `community.last_owner`: the change would leave the community account without an OWNER\n"
            + "- 409 `common.conflict`: another member change is in progress; retry\n";

    private static final String PROFILE_EDITOR = "- 403 `community.role_forbidden`: the caller is not an OWNER or ADMIN\n";
    private static final String ERR_ROLE_FORBIDDEN = ERR_MESSAGE + "Your role in this community account does not allow this action" + ERR_CODE
            + "community.role_forbidden" + ERR_ERROR + "Your role in this community account does not allow this action" + ERR_END;
    private static final String LIMIT_REACHED = "- 403 `community.limit_reached`: the user who would become an OWNER already owns as many "
            + "community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`\n";

    private static final String ERR_NOT_MEMBER = ERR_MESSAGE + "You are not a member of this community account" + ERR_CODE
            + "community.not_member" + ERR_ERROR + "You are not a member of this community account" + ERR_END;
    private static final String ERR_LAST_OWNER = ERR_MESSAGE + "A community account must keep at least one owner" + ERR_CODE
            + "community.last_owner" + ERR_ERROR + "A community account must keep at least one owner" + ERR_END;

    @PostMapping("/api/communities")
    @Auditable(action = "CREATE", resourceType = "COMMUNITY")
    @CommonApiResponses
    @Operation(summary = "Create a community account", description = """
            **Auth**: required
            **Precondition**: the caller is an active account that is not a community account; the username is free
            **Behavior**: creates a community account (a group's page: `displayName` is the group name, the logo is its avatar) \
            with the caller as its first OWNER, who then adds members. It has no email or phone and can never sign in; members \
            act as it with the `X-Act-As` header. Sent while acting, the real member becomes the OWNER. Returns 201 with the \
            account, `community` true
            **Side effects**: the creation is audit-logged
            **Errors**:
            - 400 `common.validation_failed`: `username` not 3-50 characters of [a-z0-9_] or reserved, `displayName` blank or over 100 characters
            - 400 `community.member.invalid`: the caller is suspended or is a community account
            - 409 `user.username.taken`: the username is in use
            - 409 `common.conflict`: another change to the caller's community accounts is in progress; retry
            """ + LIMIT_REACHED,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Create a community account", value = """
                            { "username": "sunrise_tower", "displayName": "Sunrise Tower Committee" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Community account created",
            content = @Content(examples = @ExampleObject(name = "Community account", value = """
                    { "success": true, "data": { "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                        "displayName": "Sunrise Tower Committee", "avatarUrl": null, "community": true },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Plan limit reached (`community.limit_reached`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.limit_reached", value = """
                            { "success": false, "data": { "limit": 1, "owned": 1 },
                              "message": "You own as many community accounts as your plan allows",
                              "errorCode": "community.limit_reached", "error": "You own as many community accounts as your plan allows" }
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Username in use (`user.username.taken`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "user.username.taken", value = ERR_MESSAGE + "Username already taken"
                            + ERR_CODE + "user.username.taken" + ERR_ERROR + "Username already taken" + ERR_END)))
    public ResponseEntity<ApiResponse<UserDto>> createOwnCommunity(@Valid @RequestBody NewCommunityRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(communityService.createCommunity(
                request.username(), request.displayName(), UUID.fromString(ActingMember.realUserId(principal)))));
    }

    @GetMapping("/api/users/me/communities")
    @CommonApiResponses
    @Operation(summary = "List my community accounts", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: the active community accounts the caller is a member of, with the caller's role, oldest membership first; \
            for the account switcher. Send an account's `communityId` as the `X-Act-As` header to act as it. While acting, the list \
            is still the real member's. `locked` accounts are readable, but writes as them answer 403 `community.locked`.
            **Side effects**: none
            **Errors**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The caller's community accounts",
            content = @Content(examples = @ExampleObject(name = "Community accounts", value = """
                    { "success": true, "data": [
                        { "communityId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower", "displayName": "Sunrise Tower Committee",
                          "avatarUrl": "/api/avatars/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d49.jpg", "role": "EDITOR", "locked": false }
                      ], "message": null, "errorCode": null, "error": null }
                    """)))
    public ResponseEntity<ApiResponse<List<MyCommunityResponse>>> listMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.listMine(ActingMember.realUserId(principal))));
    }

    @PatchMapping("/api/communities/{communityId}")
    @CommonApiResponses
    @Operation(summary = "Update a community account's profile", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: none
            **Behavior**: updates `displayName` and/or `bio`. A field that is omitted or `null` is left unchanged; an empty `bio` clears it. \
            `displayName` is stored without surrounding whitespace. The `username` can never change: a `username` in the body is ignored. \
            Returns the community account's profile after the update, as `GET /api/users/{username}` shows it.
            **Side effects**: audit-logged as `UPDATE` on `COMMUNITY`, with each changed field's value before and after in the details
            **Errors**:
            - 400 `common.validation_failed`: `displayName` blank or over 100 characters, or `bio` over 500 characters
            """ + NOT_MEMBER + PROFILE_EDITOR,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Rename", value = """
                            { "displayName": "Sunrise Tower Committee", "bio": "Residents' committee of Sunrise Tower" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile after the update",
            content = @Content(examples = @ExampleObject(name = "Profile", value = EX_PROFILE_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member, or not an OWNER or ADMIN (`community.not_member`, `community.role_forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.role_forbidden", value = ERR_ROLE_FORBIDDEN)))
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody UpdateCommunityRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.updateProfile(communityId, request.toProfileUpdate(), ActingMember.realUserId(principal))));
    }

    @PutMapping(value = "/api/communities/{communityId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @CommonApiResponses
    @StorageUnavailableResponse
    @Operation(summary = "Upload a community account's avatar", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: multipart/form-data with the image in part `file`, as `PUT /api/users/me/avatar`
            **Behavior**: processes and stores the image as `PUT /api/users/me/avatar` does (512x512 JPEG, `avatarUrl` becomes \
            `/api/avatars/<id>.jpg`). The daily upload limit counts per community account. Returns the community account's profile after the update.
            **Side effects**: audit-logged as `UPDATE` on `COMMUNITY`, with `avatarUrl` before and after in the details; once the change \
            commits, the previously uploaded image (if any) is deleted
            **Errors**:
            - 400 `common.file_required`: part `file` is missing or empty
            - 400 `common.file_read_failed`: the uploaded file could not be read
            - 400 `user.avatar.unsupported_image`: the file is not a decodable JPEG, PNG, GIF or BMP, or exceeds 40 megapixels
            - 413 `common.file_too_large`: the file exceeds 2 MB
            - 429 `common.rate_limited`: the community account already attempted the daily number of avatar uploads
            - 503 `common.storage_unavailable`: image storage is not configured or unreachable; retry later
            """ + NOT_MEMBER + PROFILE_EDITOR)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile after the update",
            content = @Content(examples = @ExampleObject(name = "Profile", value = EX_PROFILE_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member, or not an OWNER or ADMIN (`community.not_member`, `community.role_forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.role_forbidden", value = ERR_ROLE_FORBIDDEN)))
    public ResponseEntity<ApiResponse<UserProfileResponse>> uploadAvatar(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Parameter(description = "Image file (JPEG, PNG, GIF or BMP), at most 2 MB", required = true)
            @RequestPart(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.uploadAvatar(communityId, file, ActingMember.realUserId(principal))));
    }

    @DeleteMapping("/api/communities/{communityId}")
    @Auditable(action = "DELETE", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete a community account", description = """
            **Auth**: required (OWNER only)
            **Precondition**: none
            **Behavior**: permanently deletes the community account the way `DELETE /api/users/{userId}` deletes a user's: \
            its Polls go with it, together with every Ballot and Comment on them, and so do its own Comments, likes and \
            follows. Every membership is removed, so the account leaves each member's `GET /api/users/me/communities`, \
            `GET /api/users/{username}` answers 404 `user.not_found`, and a later `X-Act-As` with its id gets 403 `community.not_member`.
            **Side effects**: audit-logged as `DELETE` on `COMMUNITY` with the username, display name and members (`username:ROLE`) \
            at deletion in the details; the community account's earlier audit entries are kept
            **Errors**:
            - 403 `community.role_forbidden`: the caller is not an OWNER
            - 409 `common.conflict`: another member change is in progress; retry
            """ + NOT_MEMBER)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Community account deleted",
            content = @Content(examples = @ExampleObject(name = "Deleted", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member, or not an OWNER (`community.not_member`, `community.role_forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.role_forbidden", value = ERR_ROLE_FORBIDDEN)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteCommunity(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.deleteCommunity(communityId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @GetMapping("/api/communities/{communityId}/members")
    @CommonApiResponses
    @Operation(summary = "List community account members", description = """
            **Auth**: required (any member)
            **Precondition**: none
            **Behavior**: every member with their role, earliest first.
            **Side effects**: none
            **Errors**:
            """ + NOT_MEMBER)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member (`community.not_member`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.not_member", value = ERR_NOT_MEMBER)))
    public ResponseEntity<ApiResponse<List<CommunityMemberResponse>>> listMembers(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.listMembers(communityId, ActingMember.realUserId(principal))));
    }

    @PostMapping("/api/communities/{communityId}/members")
    @Auditable(action = "ADD_MEMBER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Add a community account member", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: the user is an active account, not a community account, and not yet a member
            **Behavior**: adds the user with the role. \
            """ + ROLES + """

            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `community.member.invalid`: the user is suspended or is a community account
            - 400 `common.validation_failed`: `userId` or `role` missing or invalid
            - 404 `user.not_found`: no user with this id
            - 409 `community.member.exists`: the user is already a member
            """ + NOT_MEMBER + ROLE_FORBIDDEN + LIMIT_REACHED,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Add an editor", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "role": "EDITOR" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Member added",
            content = @Content(examples = @ExampleObject(name = "Member", value = EX_MEMBER_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member or the role does not allow it (`community.not_member`, `community.role_forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.not_member", value = ERR_NOT_MEMBER)))
    public ResponseEntity<ApiResponse<CommunityMemberResponse>> addMember(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody CommunityMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(communityService.addMember(communityId,
                request.userId(), request.role(), ActingMember.realUserId(principal))));
    }

    @PatchMapping("/api/communities/{communityId}/members/{userId}")
    @Auditable(action = "UPDATE_MEMBER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Change a community account member's role", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: the user is a member
            **Behavior**: sets the member's role. \
            """ + ROLES + """

            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `common.validation_failed`: `role` missing or invalid
            """ + NOT_MEMBER + ROLE_FORBIDDEN + MEMBER_NOT_FOUND + LAST_OWNER + LIMIT_REACHED,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Make admin", value = """
                            { "role": "ADMIN" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Member after the change",
            content = @Content(examples = @ExampleObject(name = "Member", value = EX_MEMBER_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Would leave no OWNER (`community.last_owner`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.last_owner", value = ERR_LAST_OWNER)))
    public ResponseEntity<ApiResponse<CommunityMemberResponse>> changeRole(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Parameter(description = "Member's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable UUID userId,
            @Valid @RequestBody CommunityRoleRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.changeRole(communityId, userId, request.role(),
                ActingMember.realUserId(principal))));
    }

    @DeleteMapping("/api/communities/{communityId}/members/{userId}")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove a community account member", description = """
            **Auth**: required (OWNER or ADMIN; any member may remove themself)
            **Precondition**: the user is a member
            **Behavior**: removes the member; a member naming their own id leaves, whatever their role. Polls and Comments they published as the account stay, still naming them in `acted_by`. \
            """ + ROLES + """

            **Side effects**: the action is audit-logged.
            **Errors**:
            """ + NOT_MEMBER + ROLE_FORBIDDEN + MEMBER_NOT_FOUND + LAST_OWNER)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Member removed",
            content = @Content(examples = @ExampleObject(name = "Removed", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Would leave no OWNER (`community.last_owner`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.last_owner", value = ERR_LAST_OWNER)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> removeMember(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Parameter(description = "Member's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.removeMember(communityId, userId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/api/communities/{communityId}/owner")
    @Auditable(action = "TRANSFER_OWNER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Transfer community account ownership", description = """
            **Auth**: required (OWNER only)
            **Precondition**: the user is a member
            **Behavior**: the member becomes an OWNER and the caller an ADMIN. Naming oneself changes nothing.
            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `common.validation_failed`: `userId` missing
            - 403 `community.role_forbidden`: the caller is not an OWNER
            - 409 `common.conflict`: another member change is in progress; retry
            """ + NOT_MEMBER + MEMBER_NOT_FOUND + LIMIT_REACHED,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Transfer", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ownership transferred",
            content = @Content(examples = @ExampleObject(name = "Transferred", value = EX_OK)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> transferOwner(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody CommunityOwnerRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.transferOwner(communityId, request.userId(), ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/api/communities/{communityId}/unlock")
    @CommonApiResponses
    @Operation(summary = "Unlock a community account", description = """
            **Auth**: required (OWNER only)
            **Precondition**: the caller owns fewer unlocked community accounts than their plan allows
            **Behavior**: unlocks a locked community account, which then counts toward the caller's `communityQuota`. \
            An account that is not locked changes nothing. It counts toward every other OWNER's limit again too, so one now \
            over it gets `communityQuota.selectionRequired`.
            **Side effects**: an unlock is audit-logged as `UNLOCK` on `COMMUNITY` with `reason=OWNER_UNLOCK`.
            **Errors**:
            - 403 `community.role_forbidden`: the caller is not an OWNER
            - 409 `common.conflict`: another change to the caller's community accounts is in progress; retry
            """ + NOT_MEMBER + LIMIT_REACHED)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Unlocked",
            content = @Content(examples = @ExampleObject(name = "Unlocked", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not an OWNER, or no room in the plan (`community.role_forbidden`, `community.limit_reached`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.not_member", value = ERR_NOT_MEMBER)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unlock(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.unlock(communityId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

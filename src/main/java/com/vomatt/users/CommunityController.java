package com.vomatt.users;

import com.vomatt.common.annotation.ApiError;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.StorageUnavailableResponse;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.CommunityInvitationDetailResponse;
import com.vomatt.users.dto.CommunityInvitationRequest;
import com.vomatt.users.dto.CommunityInvitationResponse;
import com.vomatt.users.dto.MyCommunityResponse;
import com.vomatt.users.dto.NewCommunityRequest;
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

    @PostMapping("/api/communities")
    @Auditable(action = "CREATE", resourceType = "COMMUNITY")
    @CommonApiResponses
    @Operation(summary = "Create a community account", description = """
            **Auth**: required
            **Precondition**: the caller is an active account that is not a community account; the username is free
            **Behavior**: creates a community account (a group's page: `displayName` is the group name, the logo is its avatar) \
            with the caller as its first OWNER, who then invites members (`POST /api/communities/{communityId}/invitations`). It has no email or phone and can never sign in; members \
            act as it with the `X-Act-As` header. Sent while acting, the real member becomes the OWNER. Returns 201 with the \
            account, `community` true
            **Side effects**: the creation is audit-logged as `CREATE` on `COMMUNITY`, with the new account's id and username in the details
            """,
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
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`username` not 3-50 characters of [a-z0-9_] or reserved, `displayName` blank or over 100 characters")
    @ApiError(status = 400, key = MessageKey.COMMUNITY_MEMBER_INVALID, when = "the caller is suspended or is a community account")
    @ApiError(status = 409, key = MessageKey.USER_USERNAME_TAKEN, when = "the username is in use")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another change to the caller's community accounts is in progress; retry")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LIMIT_REACHED, when = "the user who would become an OWNER already owns as many community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`")
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
            **Errors**: none beyond the common ones
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
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Rename", value = """
                            { "displayName": "Sunrise Tower Committee", "bio": "Residents' committee of Sunrise Tower" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile after the update",
            content = @Content(examples = @ExampleObject(name = "Profile", value = EX_PROFILE_RESPONSE)))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`displayName` blank or over 100 characters, or `bio` over 500 characters")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER or ADMIN")
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
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile after the update",
            content = @Content(examples = @ExampleObject(name = "Profile", value = EX_PROFILE_RESPONSE)))
    @ApiError(status = 400, key = MessageKey.COMMON_FILE_REQUIRED, when = "part `file` is missing or empty")
    @ApiError(status = 400, key = MessageKey.COMMON_FILE_READ_FAILED, when = "the uploaded file could not be read")
    @ApiError(status = 400, key = MessageKey.USER_AVATAR_UNSUPPORTED_IMAGE, when = "the file is not a decodable JPEG, PNG, GIF or BMP, or exceeds 40 megapixels")
    @ApiError(status = 413, key = MessageKey.COMMON_FILE_TOO_LARGE, when = "the file exceeds 2 MB", args = { "2MB" })
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "the community account already attempted the daily number of avatar uploads")
    @ApiError(status = 503, key = MessageKey.COMMON_STORAGE_UNAVAILABLE, when = "image storage is not configured or unreachable; retry later")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER or ADMIN")
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
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Community account deleted",
            content = @Content(examples = @ExampleObject(name = "Deleted", value = EX_OK)))
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another member change is in progress; retry")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
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
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS_RESPONSE)))
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    public ResponseEntity<ApiResponse<List<CommunityMemberResponse>>> listMembers(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.listMembers(communityId, ActingMember.realUserId(principal))));
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
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Make admin", value = """
                            { "role": "ADMIN" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Member after the change",
            content = @Content(examples = @ExampleObject(name = "Member", value = EX_MEMBER_RESPONSE)))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`role` missing or invalid")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller's role does not allow this change")
    @ApiError(status = 404, key = MessageKey.COMMUNITY_MEMBER_NOT_FOUND, when = "the user is not a member")
    @ApiError(status = 409, key = MessageKey.COMMUNITY_LAST_OWNER, when = "the change would leave the community account without an OWNER")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another member change is in progress; retry")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LIMIT_REACHED, when = "the user who would become an OWNER already owns as many community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`")
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
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Member removed",
            content = @Content(examples = @ExampleObject(name = "Removed", value = EX_OK)))
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller's role does not allow this change")
    @ApiError(status = 404, key = MessageKey.COMMUNITY_MEMBER_NOT_FOUND, when = "the user is not a member")
    @ApiError(status = 409, key = MessageKey.COMMUNITY_LAST_OWNER, when = "the change would leave the community account without an OWNER")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another member change is in progress; retry")
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
            **Behavior**: the member becomes an OWNER and the caller an ADMIN. Naming oneself changes nothing. A locked \
            community account does not count toward the plan's limit, so receiving one needs no room (unlocking it later does).
            **Side effects**: the action is audit-logged.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Transfer", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ownership transferred",
            content = @Content(examples = @ExampleObject(name = "Transferred", value = EX_OK)))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`userId` missing")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another member change is in progress; retry")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 404, key = MessageKey.COMMUNITY_MEMBER_NOT_FOUND, when = "the user is not a member")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LIMIT_REACHED, when = "the user who would become an OWNER already owns as many community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> transferOwner(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody CommunityOwnerRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.transferOwner(communityId, request.userId(), ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    private static final String EX_INVITATION = """
            { "id": "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607", "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "username": "dave",
              "displayName": "Dave", "avatarUrl": "bot:boba", "email": null, "role": "EDITOR",
              "invitedAt": "2026-11-11T14:52:00+08:00", "expiresAt": "2026-11-18T14:52:00+08:00" }""";

    @PostMapping("/api/communities/{communityId}/invitations")
    @Auditable(action = "INVITE_MEMBER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Invite a user to a community account", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: the invitee has no unexpired invitation to this community account
            **Behavior**: the only way to add a member: invites a user picked by `userId` (e.g. from user search) or an \
            `email` address, exactly one, with the role; it can be used for 7 days. Nobody joins without answering: the user, \
            or the account whose email is the address, gets a `COMMUNITY_INVITATION` notification and accepts \
            (`POST /api/community-invitations/{invitationId}/accept`) or declines it. An address with no account is mailed an \
            invitation to sign up; once they sign up with it (any sign-in method that verifies the address) the notification \
            waits for them like for anyone else. For an `email` the response \
            is the same whether or not the address belongs to an account, or to a member. An expired invitation of the same \
            user or address is replaced by the new one. \
            """ + ROLES + """

            **Side effects**: an address with no account at all is mailed once committed; one member mails one address at most \
            once a week, within the member's own daily invitation email limit (shared with their Poll and Invite List invitations). Audit-logged.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
                    @ExampleObject(name = "Invite a user", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "role": "EDITOR" }
                            """),
                    @ExampleObject(name = "Invite an email address", value = """
                            { "email": "dave@example.com", "role": "ANALYST" }
                            """) })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invitation sent",
            content = @Content(examples = @ExampleObject(name = "Invitation", value = "{ \"success\": true, \"data\": " + EX_INVITATION
                    + ", \"message\": null, \"errorCode\": null, \"error\": null }")))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "neither or both of `userId` and `email`, `email` invalid, or `role` missing or invalid")
    @ApiError(status = 400, key = MessageKey.COMMUNITY_MEMBER_INVALID, when = "the `userId` is suspended or is a community account")
    @ApiError(status = 404, key = MessageKey.USER_NOT_FOUND, when = "no user with this `userId`")
    @ApiError(status = 409, key = MessageKey.COMMUNITY_MEMBER_EXISTS, when = "the `userId` is already a member")
    @ApiError(status = 409, key = MessageKey.COMMUNITY_INVITATION_EXISTS, when = "the user or address already has an unexpired invitation to this community account")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER or ADMIN, or is an ADMIN and the invitation grants OWNER")
    public ResponseEntity<ApiResponse<CommunityInvitationResponse>> invite(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody CommunityInvitationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(communityService.invite(communityId,
                request.userId(), request.email(), request.role(), ActingMember.realUserId(principal))));
    }

    @GetMapping("/api/communities/{communityId}/invitations")
    @CommonApiResponses
    @Operation(summary = "List a community account's invitations", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: none
            **Behavior**: the invitations not yet accepted, declined or revoked, oldest first; expired ones are included \
            (compare `expiresAt` with now) until the address is invited again.
            **Side effects**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitations",
            content = @Content(examples = @ExampleObject(name = "Invitations", value = "{ \"success\": true, \"data\": [" + EX_INVITATION
                    + "], \"message\": null, \"errorCode\": null, \"error\": null }")))
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER or ADMIN")
    public ResponseEntity<ApiResponse<List<CommunityInvitationResponse>>> listInvitations(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.listInvitations(communityId, ActingMember.realUserId(principal))));
    }

    @DeleteMapping("/api/communities/{communityId}/invitations/{invitationId}")
    @Auditable(action = "REVOKE_INVITATION", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Revoke a community account invitation", description = """
            **Auth**: required (OWNER or ADMIN; only an OWNER revokes one that grants OWNER)
            **Precondition**: the invitation belongs to this community account
            **Behavior**: deletes the invitation, expired or not; its notification disappears and it can no longer be accepted. \
            An email already sent stays sent.
            **Side effects**: the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation revoked",
            content = @Content(examples = @ExampleObject(name = "Revoked", value = EX_OK)))
    @ApiError(status = 404, key = MessageKey.COMMUNITY_INVITATION_NOT_FOUND, when = "no such invitation of this community account")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER or ADMIN, or is an ADMIN and the invitation grants OWNER")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> revokeInvitation(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.revokeInvitation(communityId, invitationId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @GetMapping("/api/community-invitations/{invitationId}")
    @CommonApiResponses
    @Operation(summary = "Get a community account invitation sent to the caller", description = """
            **Auth**: required; refused while acting as a community account (`X-Act-As`)
            **Precondition**: the invitation was sent to the caller (by id or to their email address) and has not expired
            **Behavior**: the invitation as its invitee sees it, for the page the invitation email links to: the role, \
            when it expires, the community account and the member who sent it (`inviter`, null once their account is \
            deleted), each with public names masked by their visibility settings. `locked`: the community account is locked, so accept answers 403 `community.locked` until it is unlocked. `member`: the caller already belongs to it, so accepting keeps their role. \
            Visible exactly when accept or decline would find it; any other caller gets the same 404, so it never tells whether the invitation exists. The \
            invitation id is the one in the email link, or `communityInvitation.id` of the `COMMUNITY_INVITATION` notification.
            **Side effects**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation",
            content = @Content(examples = @ExampleObject(name = "Invitation", value = """
                    { "success": true, "data": { "id": "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607", "role": "EDITOR",
                        "expiresAt": "2026-11-18T14:52:00+08:00",
                        "community": { "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                          "displayName": "Sunrise Tower Committee", "firstName": null, "lastName": null, "avatarUrl": null,
                          "community": true },
                        "inviter": { "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "username": "amy", "displayName": "Amy",
                          "firstName": null, "lastName": null, "avatarUrl": "bot:boba", "community": false }, "locked": false, "member": false },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @ApiError(status = 404, key = MessageKey.COMMUNITY_INVITATION_NOT_FOUND, when = "no unexpired invitation with this id was sent to the caller, or the community account is suspended")
    public ResponseEntity<ApiResponse<CommunityInvitationDetailResponse>> getInvitation(
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.getInvitation(invitationId, ActingMember.realUserId(principal))));
    }

    @PostMapping("/api/community-invitations/{invitationId}/accept")
    @Auditable(action = "ACCEPT_INVITATION", resourceType = "COMMUNITY_INVITATION", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Accept a community account invitation", description = """
            **Auth**: required; refused while acting as a community account (`X-Act-As`)
            **Precondition**: the invitation was sent to the caller (by id or to their email address) and has not expired
            **Behavior**: the caller joins the community account with the invitation's role; every invitation of this community \
            account to the caller (by id and to their address) is used up. \
            The invitation id is `communityInvitation.id` of the `COMMUNITY_INVITATION` notification. A caller already a \
            member keeps their current role. Returns the membership as `GET /api/users/me/communities` lists it.
            **Side effects**: the invitation and its notification disappear; the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Joined",
            content = @Content(examples = @ExampleObject(name = "Membership", value = """
                    { "success": true, "data": { "communityId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                        "displayName": "Sunrise Tower Committee", "avatarUrl": null, "role": "EDITOR", "locked": false },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @ApiError(status = 400, key = MessageKey.COMMUNITY_MEMBER_INVALID, when = "the caller is suspended or is a community account")
    @ApiError(status = 404, key = MessageKey.COMMUNITY_INVITATION_NOT_FOUND, when = "no unexpired invitation with this id was sent to the caller, or the community account is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LOCKED, when = "the community account is locked; the invitation stays and can be accepted once it is unlocked")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LIMIT_REACHED, when = "the user who would become an OWNER already owns as many community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`")
    public ResponseEntity<ApiResponse<MyCommunityResponse>> acceptInvitation(
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.acceptInvitation(invitationId, ActingMember.realUserId(principal))));
    }

    @PostMapping("/api/community-invitations/{invitationId}/decline")
    @Auditable(action = "DECLINE_INVITATION", resourceType = "COMMUNITY_INVITATION", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Decline a community account invitation", description = """
            **Auth**: required; refused while acting as a community account (`X-Act-As`)
            **Precondition**: the invitation was sent to the caller (by id or to their email address) and has not expired
            **Behavior**: deletes the invitation; the community account can invite the address again.
            **Side effects**: the invitation and its notification disappear; the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Declined",
            content = @Content(examples = @ExampleObject(name = "Declined", value = EX_OK)))
    @ApiError(status = 404, key = MessageKey.COMMUNITY_INVITATION_NOT_FOUND, when = "no unexpired invitation with this id was sent to the caller")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> declineInvitation(
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.declineInvitation(invitationId, ActingMember.realUserId(principal));
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
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Unlocked",
            content = @Content(examples = @ExampleObject(name = "Unlocked", value = EX_OK)))
    @ApiError(status = 403, key = MessageKey.COMMUNITY_ROLE_FORBIDDEN, when = "the caller is not an OWNER")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "another change to the caller's community accounts is in progress; retry")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_NOT_MEMBER, when = "the caller is not a member of this community account, or it does not exist or is suspended")
    @ApiError(status = 403, key = MessageKey.COMMUNITY_LIMIT_REACHED, when = "the user who would become an OWNER already owns as many community accounts as their plan allows; `data` is `{ \"limit\": 1, \"owned\": 1 }`")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> unlock(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.unlock(communityId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

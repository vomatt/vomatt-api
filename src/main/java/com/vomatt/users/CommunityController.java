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
import com.vomatt.users.dto.CommunityInvitationRequest;
import com.vomatt.users.dto.CommunityInvitationResponse;
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
            is still the real member's.
            **Side effects**: none
            **Errors**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The caller's community accounts",
            content = @Content(examples = @ExampleObject(name = "Community accounts", value = """
                    { "success": true, "data": [
                        { "communityId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower", "displayName": "Sunrise Tower Committee",
                          "avatarUrl": "/api/avatars/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d49.jpg", "role": "EDITOR" }
                      ], "message": null, "errorCode": null, "error": null }
                    """)))
    public ResponseEntity<ApiResponse<List<MyCommunityResponse>>> listMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.listMine(ActingMember.realUserId(principal))));
    }

    @PatchMapping("/api/communities/{communityId}")
    @Auditable(action = "UPDATE", resourceType = "COMMUNITY", resourceIdIndex = 0)
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
        return ResponseEntity.ok(ApiResponse.ok(communityService.updateProfile(communityId, request, ActingMember.realUserId(principal))));
    }

    @PutMapping(value = "/api/communities/{communityId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Auditable(action = "UPDATE", resourceType = "COMMUNITY", resourceIdIndex = 0)
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
            """ + NOT_MEMBER + ROLE_FORBIDDEN,
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
            """ + NOT_MEMBER + ROLE_FORBIDDEN + MEMBER_NOT_FOUND + LAST_OWNER,
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
            """ + NOT_MEMBER + MEMBER_NOT_FOUND,
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

    private static final String EX_INVITATION = """
            { "id": "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607", "email": "dave@example.com", "role": "EDITOR",
              "invitedAt": "2026-11-11T14:52:00+08:00", "expiresAt": "2026-11-18T14:52:00+08:00" }""";
    private static final String MANAGER_ONLY = "- 403 `community.role_forbidden`: the caller is not an OWNER or ADMIN, "
            + "or is an ADMIN and the invitation grants OWNER\n";
    private static final String INVITATION_NOT_FOUND = ERR_MESSAGE + "Invitation not found or expired" + ERR_CODE
            + "community.invitation.not_found" + ERR_ERROR + "Invitation not found or expired" + ERR_END;

    @PostMapping("/api/communities/{communityId}/invitations")
    @Auditable(action = "INVITE_MEMBER", resourceType = "COMMUNITY", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Invite an email address to a community account", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: the address has no unexpired invitation to this community account
            **Behavior**: invites the address with the role; it can be used for 7 days. The response is the same whether or not \
            the address belongs to an account, and whether or not that account is already a member. An address with an account \
            gets a `COMMUNITY_INVITATION` notification and accepts (`POST /api/community-invitations/{invitationId}/accept`) or \
            declines it; an address with no account is mailed, and signing up with it (any sign-in method that verifies the \
            address) joins with the role. An expired invitation of the address is replaced by the new one. \
            """ + ROLES + """

            **Side effects**: an address with no account at all is mailed once committed; one member mails one address at most \
            once a week, within the member's own daily invitation email limit (shared with their Poll and Invite List invitations). Audit-logged.
            **Errors**:
            - 400 `common.validation_failed`: `email` missing or invalid, or `role` missing or invalid
            - 409 `community.invitation.exists`: the address already has an unexpired invitation to this community account
            """ + NOT_MEMBER + MANAGER_ONLY,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Invite an editor", value = """
                            { "email": "dave@example.com", "role": "EDITOR" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invitation sent",
            content = @Content(examples = @ExampleObject(name = "Invitation", value = "{ \"success\": true, \"data\": " + EX_INVITATION
                    + ", \"message\": null, \"errorCode\": null, \"error\": null }")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already invited (`community.invitation.exists`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.invitation.exists", value = ERR_MESSAGE
                            + "This email address already has a pending invitation" + ERR_CODE + "community.invitation.exists"
                            + ERR_ERROR + "This email address already has a pending invitation" + ERR_END)))
    public ResponseEntity<ApiResponse<CommunityInvitationResponse>> invite(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Valid @RequestBody CommunityInvitationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(communityService.invite(communityId,
                request.email(), request.role(), ActingMember.realUserId(principal))));
    }

    @GetMapping("/api/communities/{communityId}/invitations")
    @CommonApiResponses
    @Operation(summary = "List a community account's invitations", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: none
            **Behavior**: the invitations not yet accepted, declined or revoked, oldest first; expired ones are included \
            (compare `expiresAt` with now) until the address is invited again.
            **Side effects**: none
            **Errors**:
            """ + NOT_MEMBER + PROFILE_EDITOR)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitations",
            content = @Content(examples = @ExampleObject(name = "Invitations", value = "{ \"success\": true, \"data\": [" + EX_INVITATION
                    + "], \"message\": null, \"errorCode\": null, \"error\": null }")))
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
            **Behavior**: deletes the invitation, expired or not; its notification disappears and it can no longer be accepted \
            or joined by signing up. An email already sent stays sent.
            **Side effects**: the action is audit-logged.
            **Errors**:
            - 404 `community.invitation.not_found`: no such invitation of this community account
            """ + NOT_MEMBER + MANAGER_ONLY)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation revoked",
            content = @Content(examples = @ExampleObject(name = "Revoked", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such invitation (`community.invitation.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.invitation.not_found", value = INVITATION_NOT_FOUND)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> revokeInvitation(
            @Parameter(description = "Community account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID communityId,
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.revokeInvitation(communityId, invitationId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/api/community-invitations/{invitationId}/accept")
    @Auditable(action = "ACCEPT_INVITATION", resourceType = "COMMUNITY_INVITATION", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Accept a community account invitation", description = """
            **Auth**: required; refused while acting as a community account (`X-Act-As`)
            **Precondition**: the invitation was sent to the caller's email address and has not expired
            **Behavior**: the caller joins the community account with the invitation's role and the invitation is used up. \
            The invitation id is `communityInvitation.id` of the `COMMUNITY_INVITATION` notification. A caller already a \
            member keeps their current role. Returns the membership as `GET /api/users/me/communities` lists it.
            **Side effects**: the invitation and its notification disappear; the action is audit-logged.
            **Errors**:
            - 400 `community.member.invalid`: the caller is suspended or is a community account
            - 404 `community.invitation.not_found`: no unexpired invitation with this id was sent to the caller's address, \
            or the community account is suspended
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Joined",
            content = @Content(examples = @ExampleObject(name = "Membership", value = """
                    { "success": true, "data": { "communityId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                        "displayName": "Sunrise Tower Committee", "avatarUrl": null, "role": "EDITOR" },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such invitation for the caller (`community.invitation.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.invitation.not_found", value = INVITATION_NOT_FOUND)))
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
            **Precondition**: the invitation was sent to the caller's email address and has not expired
            **Behavior**: deletes the invitation; the community account can invite the address again.
            **Side effects**: the invitation and its notification disappear; the action is audit-logged.
            **Errors**:
            - 404 `community.invitation.not_found`: no unexpired invitation with this id was sent to the caller's address
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Declined",
            content = @Content(examples = @ExampleObject(name = "Declined", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such invitation for the caller (`community.invitation.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "community.invitation.not_found", value = INVITATION_NOT_FOUND)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> declineInvitation(
            @Parameter(description = "Invitation id", required = true, example = "019a6b2c-3d4e-7f50-8a61-b2c3d4e5f607")
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal UserPrincipal principal) {
        communityService.declineInvitation(invitationId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

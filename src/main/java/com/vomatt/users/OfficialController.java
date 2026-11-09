package com.vomatt.users;

import static com.vomatt.common.config.OpenAPIConfig.ERR_CODE;
import static com.vomatt.common.config.OpenAPIConfig.ERR_END;
import static com.vomatt.common.config.OpenAPIConfig.ERR_ERROR;
import static com.vomatt.common.config.OpenAPIConfig.ERR_MESSAGE;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.users.dto.MyOfficialResponse;
import com.vomatt.users.dto.OfficialMemberRequest;
import com.vomatt.users.dto.OfficialMemberResponse;
import com.vomatt.users.dto.OfficialOwnerRequest;
import com.vomatt.users.dto.OfficialRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Official account members. Authorized by the caller's own membership (the real member, whether or not the request
 * also sends {@code X-Act-As}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Official Account", description = "Official accounts (a group's page) and the members who manage them")
public class OfficialController {

    private final OfficialService officialService;

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

    private static final String ROLES = """
            Roles: OWNER and ADMIN manage members; an ADMIN never changes or removes an OWNER nor grants OWNER. \
            EDITOR and ANALYST cannot manage members.""";
    private static final String NOT_MEMBER = "- 403 `official.not_member`: the caller is not a member of this official account, or it does not exist or is suspended\n";
    private static final String ROLE_FORBIDDEN = "- 403 `official.role_forbidden`: the caller's role does not allow this change\n";
    private static final String MEMBER_NOT_FOUND = "- 404 `official.member.not_found`: the user is not a member\n";
    private static final String LAST_OWNER = "- 409 `official.last_owner`: the change would leave the official account without an OWNER\n"
            + "- 409 `common.conflict`: another member change is in progress; retry\n";

    private static final String ERR_NOT_MEMBER = ERR_MESSAGE + "You are not a member of this official account" + ERR_CODE
            + "official.not_member" + ERR_ERROR + "You are not a member of this official account" + ERR_END;
    private static final String ERR_LAST_OWNER = ERR_MESSAGE + "An official account must keep at least one owner" + ERR_CODE
            + "official.last_owner" + ERR_ERROR + "An official account must keep at least one owner" + ERR_END;

    @GetMapping("/api/users/me/officials")
    @CommonApiResponses
    @Operation(summary = "List my official accounts", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: the active official accounts the caller is a member of, with the caller's role, oldest membership first; \
            for the account switcher. Send an account's `officialId` as the `X-Act-As` header to act as it. While acting, the list \
            is still the real member's.
            **Side effects**: none
            **Errors**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The caller's official accounts",
            content = @Content(examples = @ExampleObject(name = "Official accounts", value = """
                    { "success": true, "data": [
                        { "officialId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower", "displayName": "Sunrise Tower Committee",
                          "avatarUrl": "/api/avatars/0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d49.jpg", "role": "EDITOR" }
                      ], "message": null, "errorCode": null, "error": null }
                    """)))
    public ResponseEntity<ApiResponse<List<MyOfficialResponse>>> listMine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(officialService.listMine(ActingMember.realUserId(principal))));
    }

    @GetMapping("/api/officials/{officialId}/members")
    @CommonApiResponses
    @Operation(summary = "List official account members", description = """
            **Auth**: required (any member)
            **Precondition**: none
            **Behavior**: every member with their role, earliest first.
            **Side effects**: none
            **Errors**:
            """ + NOT_MEMBER)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member (`official.not_member`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "official.not_member", value = ERR_NOT_MEMBER)))
    public ResponseEntity<ApiResponse<List<OfficialMemberResponse>>> listMembers(
            @Parameter(description = "Official account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID officialId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(officialService.listMembers(officialId, ActingMember.realUserId(principal))));
    }

    @PostMapping("/api/officials/{officialId}/members")
    @Auditable(action = "ADD_MEMBER", resourceType = "OFFICIAL", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Add an official account member", description = """
            **Auth**: required (OWNER or ADMIN)
            **Precondition**: the user is an active account, not an official account, and not yet a member
            **Behavior**: adds the user with the role. \
            """ + ROLES + """

            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `official.member.invalid`: the user is suspended or is an official account
            - 400 `common.validation_failed`: `userId` or `role` missing or invalid
            - 404 `user.not_found`: no user with this id
            - 409 `official.member.exists`: the user is already a member
            """ + NOT_MEMBER + ROLE_FORBIDDEN,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Add an editor", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47", "role": "EDITOR" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Member added",
            content = @Content(examples = @ExampleObject(name = "Member", value = EX_MEMBER_RESPONSE)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a member or the role does not allow it (`official.not_member`, `official.role_forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "official.not_member", value = ERR_NOT_MEMBER)))
    public ResponseEntity<ApiResponse<OfficialMemberResponse>> addMember(
            @Parameter(description = "Official account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID officialId,
            @Valid @RequestBody OfficialMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(officialService.addMember(officialId,
                request.userId(), request.role(), ActingMember.realUserId(principal))));
    }

    @PatchMapping("/api/officials/{officialId}/members/{userId}")
    @Auditable(action = "UPDATE_MEMBER", resourceType = "OFFICIAL", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Change an official account member's role", description = """
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Would leave no OWNER (`official.last_owner`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "official.last_owner", value = ERR_LAST_OWNER)))
    public ResponseEntity<ApiResponse<OfficialMemberResponse>> changeRole(
            @Parameter(description = "Official account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID officialId,
            @Parameter(description = "Member's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable UUID userId,
            @Valid @RequestBody OfficialRoleRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(officialService.changeRole(officialId, userId, request.role(),
                ActingMember.realUserId(principal))));
    }

    @DeleteMapping("/api/officials/{officialId}/members/{userId}")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "OFFICIAL", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an official account member", description = """
            **Auth**: required (OWNER or ADMIN; any member may remove themself)
            **Precondition**: the user is a member
            **Behavior**: removes the member; a member naming their own id leaves, whatever their role. Polls and Comments they published as the account stay, still naming them in `acted_by`. \
            """ + ROLES + """

            **Side effects**: the action is audit-logged.
            **Errors**:
            """ + NOT_MEMBER + ROLE_FORBIDDEN + MEMBER_NOT_FOUND + LAST_OWNER)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Member removed",
            content = @Content(examples = @ExampleObject(name = "Removed", value = EX_OK)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Would leave no OWNER (`official.last_owner`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "official.last_owner", value = ERR_LAST_OWNER)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> removeMember(
            @Parameter(description = "Official account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID officialId,
            @Parameter(description = "Member's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47")
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        officialService.removeMember(officialId, userId, ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/api/officials/{officialId}/owner")
    @Auditable(action = "TRANSFER_OWNER", resourceType = "OFFICIAL", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Transfer official account ownership", description = """
            **Auth**: required (OWNER only)
            **Precondition**: the user is a member
            **Behavior**: the member becomes an OWNER and the caller an ADMIN. Naming oneself changes nothing.
            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `common.validation_failed`: `userId` missing
            - 403 `official.role_forbidden`: the caller is not an OWNER
            """ + NOT_MEMBER + MEMBER_NOT_FOUND,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Transfer", value = """
                            { "userId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ownership transferred",
            content = @Content(examples = @ExampleObject(name = "Transferred", value = EX_OK)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> transferOwner(
            @Parameter(description = "Official account's user id", required = true, example = "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48")
            @PathVariable UUID officialId,
            @Valid @RequestBody OfficialOwnerRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        officialService.transferOwner(officialId, request.userId(), ActingMember.realUserId(principal));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

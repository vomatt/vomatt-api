package com.vomatt.invitelists;

import com.vomatt.common.annotation.ApiError;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.invitelists.dto.InviteListMemberResponse;
import com.vomatt.invitelists.dto.InviteListMembersRequest;
import com.vomatt.invitelists.dto.InviteListRequest;
import com.vomatt.invitelists.dto.InviteListResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/invite-lists")
@RequiredArgsConstructor
@Tag(name = "Invite List", description = "Invite Lists: the caller's private, reusable invite lists of users and email addresses")
public class InviteListController {

    private final InviteListService inviteListService;

    private static final String EX_INVITE_LIST = """
            { "success": true, "data": {
                "id": "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07", "name": "Book club", "memberCount": 3, "createdAt": "2026-10-09T15:20:31+08:00"
              }, "message": null, "errorCode": null, "error": null }
            """;

    private static final String EX_MEMBERS = """
            { "success": true, "data": [
                { "userId": "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82", "username": "bob", "email": null, "addedAt": "2026-10-09T15:20:31+08:00" },
                { "userId": null, "username": null, "email": "dave@example.com", "addedAt": "2026-10-09T15:20:31+08:00" }
              ], "message": null, "errorCode": null, "error": null }
            """;

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List my Invite Lists", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: the caller's Invite Lists by name, each with its member count. Nobody else ever sees them, members included.
            **Side effects**: none
            **Errors**: none beyond the common ones
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The caller's Invite Lists",
            content = @Content(examples = @ExampleObject(name = "Invite Lists", value = """
                    { "success": true, "data": [
                        { "id": "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07", "name": "Book club", "memberCount": 3, "createdAt": "2026-10-09T15:20:31+08:00" }
                      ], "message": null, "errorCode": null, "error": null }
                    """)))
    public ResponseEntity<ApiResponse<List<InviteListResponse>>> getInviteLists(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.getInviteLists(principal.userId())));
    }

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "INVITE_LIST")
    @CommonApiResponses
    @Operation(summary = "Create an Invite List", description = """
            **Auth**: required
            **Precondition**: the caller has fewer Invite Lists than the limit (default 50, admin-configurable)
            **Behavior**: creates an empty Invite List owned by the caller; add members through `POST /api/invite-lists/{inviteListId}/members`.
            **Side effects**: the creation is audit-logged.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Book club", value = """
                            { "name": "Book club" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invite List created",
            content = @Content(examples = @ExampleObject(name = "Invite List", value = """
                    { "success": true, "data": {
                        "id": "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07", "name": "Book club", "memberCount": 0, "createdAt": "2026-10-09T15:20:31+08:00"
                      }, "message": null, "errorCode": null, "error": null }
                    """)))
    @ApiError(status = 400, key = MessageKey.INVITE_LIST_MAX_REACHED, when = "the caller already has as many Invite Lists as the limit (default 50)", args = { "50" })
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`name` is blank or longer than 50 characters")
    @ApiError(status = 404, key = MessageKey.USER_NOT_FOUND, when = "the signed-in user no longer exists")
    public ResponseEntity<ApiResponse<InviteListResponse>> createInviteList(
            @Valid @RequestBody InviteListRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(inviteListService.createInviteList(request, principal.userId())));
    }

    @PutMapping("/{inviteListId}")
    @Auditable(action = "UPDATE", resourceType = "INVITE_LIST", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Rename an Invite List", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: renames the Invite List.
            **Side effects**: the action is audit-logged.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Rename", value = """
                            { "name": "Book club 2026" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invite List renamed",
            content = @Content(examples = @ExampleObject(name = "Invite List", value = EX_INVITE_LIST)))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`name` is blank or longer than 50 characters")
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<InviteListResponse>> renameInviteList(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @Valid @RequestBody InviteListRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.renameInviteList(inviteListId, request, principal.userId())));
    }

    @DeleteMapping("/{inviteListId}")
    @Auditable(action = "DELETE", resourceType = "INVITE_LIST", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete an Invite List", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: deletes the Invite List and its members. Polls the Invite List was already invited into keep their Invitees.
            **Side effects**: nobody is notified; the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invite List deleted",
            content = @Content(examples = @ExampleObject(name = "Deleted", value = """
                    { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteInviteList(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @AuthenticationPrincipal UserPrincipal principal) {
        inviteListService.deleteInviteList(inviteListId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @GetMapping("/{inviteListId}/members")
    @CommonApiResponses
    @Operation(summary = "List Invite List members", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: the Invite List's members, earliest added first. A member added by email is always listed by that address \
            with `userId` / `username` null, whether or not the address belongs to an account.
            **Side effects**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members of the Invite List",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<List<InviteListMemberResponse>>> getMembers(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.getMembers(inviteListId, principal.userId())));
    }

    @PostMapping("/{inviteListId}/members")
    @Auditable(action = "ADD_MEMBERS", resourceType = "INVITE_LIST", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Add Invite List members", description = """
            **Auth**: required (owner only)
            **Precondition**: the Invite List stays within the member limit (default 100, admin-configurable). A list already over a lowered limit is kept but accepts no new members
            **Behavior**: adds the users and email addresses as members. Users already members by ID, addresses already members, duplicates and the owner's own ID / address are ignored. \
            An address is always listed as entered, whether or not it belongs to an account, including one of a user already a member by ID; once someone verifies it at sign-up / sign-in it is tied to that user, \
            still listed by the address. Returns the full member list.
            **Side effects**: members get no notification. A new address that belongs to no account at all is mailed, once committed, \
            an invitation to sign up for Vomatt from the owner, naming no Invite List; one Invite List mails one address at most once a week, \
            within the daily invitation email limit of the person adding it (the real member while acting as a community account; \
            shared with Poll invitations). The response is the same whether or not \
            an email was sent. The action is audit-logged.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Add bob and dave", value = """
                            { "userIds": [ "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82" ], "emails": [ "dave@example.com" ] }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the addition",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @ApiError(status = 400, key = MessageKey.INVITE_LIST_MEMBER_IDS_INVALID, when = "one of `userIds` is not an existing user or is suspended (`emails` never cause it)")
    @ApiError(status = 400, key = MessageKey.COMMUNITY_INVITEE_NOT_ALLOWED, when = "one of `userIds` is a community account")
    @ApiError(status = 400, key = MessageKey.INVITE_LIST_MEMBERS_MAX, when = "the Invite List would hold more members than the limit (default 100), including when it is already over a lowered limit", args = { "100" })
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "both `userIds` and `emails` are empty, one holds more than 100 entries, a null element or an invalid address")
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<List<InviteListMemberResponse>>> addMembers(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @Valid @RequestBody InviteListMembersRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.addMembers(inviteListId, request, principal.userId())));
    }

    @DeleteMapping("/{inviteListId}/members/{userId}")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "INVITE_LIST", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an Invite List member", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: removes the member added by this user ID. Idempotent: succeeds, changing nothing, when the user is not a member added by ID; \
            one added by email is removed through `DELETE /api/invite-lists/{inviteListId}/members?email=`. Polls the Invite List was already invited into keep their Invitees. \
            Returns the full member list.
            **Side effects**: the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the removal",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<List<InviteListMemberResponse>>> removeMember(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @Parameter(description = "User ID of the member", required = true, example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.removeMember(inviteListId, userId, principal.userId())));
    }

    @DeleteMapping(value = "/{inviteListId}/members", params = "email")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "INVITE_LIST", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an email Invite List member", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: removes the member added by this address (normalized like sign-in does: lower-cased, trimmed, Gmail in canonical form), whether or not it belongs to an account. \
            Idempotent: succeeds, changing nothing, when the address is not a member. Polls the Invite List was already invited into keep their Invitees. \
            Returns the full member list.
            **Side effects**: the action is audit-logged.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the removal",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @ApiError(status = 404, key = MessageKey.INVITE_LIST_NOT_FOUND, when = "the Invite List does not exist or belongs to someone else")
    public ResponseEntity<ApiResponse<List<InviteListMemberResponse>>> removeEmailMember(
            @Parameter(description = "Invite List ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String inviteListId,
            @Parameter(description = "Email address the member was added by", required = true, example = "dave@example.com")
            @RequestParam String email,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteListService.removeEmailMember(inviteListId, email, principal.userId())));
    }
}

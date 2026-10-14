package com.vomatt.groups;

import static com.vomatt.common.config.OpenAPIConfig.ERR_CODE;
import static com.vomatt.common.config.OpenAPIConfig.ERR_END;
import static com.vomatt.common.config.OpenAPIConfig.ERR_ERROR;
import static com.vomatt.common.config.OpenAPIConfig.ERR_MESSAGE;

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
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.groups.dto.GroupMemberResponse;
import com.vomatt.groups.dto.GroupMembersRequest;
import com.vomatt.groups.dto.GroupRequest;
import com.vomatt.groups.dto.GroupResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group", description = "Groups: the caller's private, reusable invite lists of users and email addresses")
public class GroupController {

    private final GroupService groupService;

    private static final String EX_GROUP = """
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

    private static final String ERR_GROUP_NOT_FOUND = ERR_MESSAGE + "Group not found" + ERR_CODE + "group.not_found" + ERR_ERROR + "Group not found" + ERR_END;

    private static final String NOT_FOUND = "- 404 `group.not_found`: the Group does not exist or belongs to someone else";

    @GetMapping
    @CommonApiResponses
    @Operation(summary = "List my Groups", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: the caller's Groups by name, each with its member count. Nobody else ever sees them, members included.
            **Side effects**: none
            **Errors**: none
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The caller's Groups",
            content = @Content(examples = @ExampleObject(name = "Groups", value = """
                    { "success": true, "data": [
                        { "id": "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07", "name": "Book club", "memberCount": 3, "createdAt": "2026-10-09T15:20:31+08:00" }
                      ], "message": null, "errorCode": null, "error": null }
                    """)))
    public ResponseEntity<ApiResponse<List<GroupResponse>>> getGroups(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.getGroups(principal.userId())));
    }

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "GROUP")
    @CommonApiResponses
    @Operation(summary = "Create a Group", description = """
            **Auth**: required
            **Precondition**: the caller has fewer than 50 Groups
            **Behavior**: creates an empty Group owned by the caller; add members through `POST /api/groups/{groupId}/members`.
            **Side effects**: the creation is audit-logged.
            **Errors**:
            - 400 `group.max_reached`: the caller already has 50 Groups
            - 400 `common.validation_failed`: `name` is blank or longer than 50 characters
            - 404 `user.not_found`: the signed-in user no longer exists
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Book club", value = """
                            { "name": "Book club" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Group created",
            content = @Content(examples = @ExampleObject(name = "Group", value = """
                    { "success": true, "data": {
                        "id": "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07", "name": "Book club", "memberCount": 0, "createdAt": "2026-10-09T15:20:31+08:00"
                      }, "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The caller already has 50 Groups (`group.max_reached`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.max_reached", value = ERR_MESSAGE + "You can keep at most 50 Groups" + ERR_CODE + "group.max_reached" + ERR_ERROR + "You can keep at most 50 Groups" + ERR_END)))
    public ResponseEntity<ApiResponse<GroupResponse>> createGroup(
            @Valid @RequestBody GroupRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(groupService.createGroup(request, principal.userId())));
    }

    @PutMapping("/{groupId}")
    @Auditable(action = "UPDATE", resourceType = "GROUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Rename a Group", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: renames the Group.
            **Side effects**: the action is audit-logged.
            **Errors**:
            - 400 `common.validation_failed`: `name` is blank or longer than 50 characters
            """ + NOT_FOUND,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Rename", value = """
                            { "name": "Book club 2026" }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Group renamed",
            content = @Content(examples = @ExampleObject(name = "Group", value = EX_GROUP)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<GroupResponse>> renameGroup(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @Valid @RequestBody GroupRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.renameGroup(groupId, request, principal.userId())));
    }

    @DeleteMapping("/{groupId}")
    @Auditable(action = "DELETE", resourceType = "GROUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Delete a Group", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: deletes the Group and its members. Polls the Group was already invited into keep their Invitees.
            **Side effects**: nobody is notified; the action is audit-logged.
            **Errors**:
            """ + NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Group deleted",
            content = @Content(examples = @ExampleObject(name = "Deleted", value = """
                    { "success": true, "data": { "success": true, "id": null, "status": null, "message": null },
                      "message": null, "errorCode": null, "error": null }
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteGroup(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @AuthenticationPrincipal UserPrincipal principal) {
        groupService.deleteGroup(groupId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @GetMapping("/{groupId}/members")
    @CommonApiResponses
    @Operation(summary = "List Group members", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: the Group's members, earliest added first. A member added by email is always listed by that address \
            with `userId` / `username` null, whether or not the address belongs to an account.
            **Side effects**: none
            **Errors**:
            """ + NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members of the Group",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<List<GroupMemberResponse>>> getMembers(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.getMembers(groupId, principal.userId())));
    }

    @PostMapping("/{groupId}/members")
    @Auditable(action = "ADD_MEMBERS", resourceType = "GROUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Add Group members", description = """
            **Auth**: required (owner only)
            **Precondition**: the Group stays within 100 members
            **Behavior**: adds the users and email addresses as members. Members already in the Group, duplicates and the owner's own ID / address are ignored. \
            An address is always listed as entered, whether or not it belongs to an account; once someone verifies it at sign-up / sign-in it is tied to that user, \
            still listed by the address. Returns the full member list.
            **Side effects**: nobody is notified (members never learn about the Group); the action is audit-logged.
            **Errors**:
            - 400 `group.member.ids_invalid`: one of `userIds` is not an existing user or is suspended (`emails` never cause it)
            - 400 `group.members.max`: the Group would hold more than 100 members
            - 400 `common.validation_failed`: both `userIds` and `emails` are empty, one holds more than 100 entries, a null element or an invalid address
            """ + NOT_FOUND,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Add bob and dave", value = """
                            { "userIds": [ "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82" ], "emails": [ "dave@example.com" ] }
                            """))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the addition",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A user does not exist (`group.member.ids_invalid`) or the Group would exceed 100 members (`group.members.max`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = {
                    @ExampleObject(name = "group.member.ids_invalid", value = ERR_MESSAGE + "One or more users do not exist or are suspended" + ERR_CODE + "group.member.ids_invalid" + ERR_ERROR + "One or more users do not exist or are suspended" + ERR_END),
                    @ExampleObject(name = "group.members.max", value = ERR_MESSAGE + "A Group can hold at most 100 members" + ERR_CODE + "group.members.max" + ERR_ERROR + "A Group can hold at most 100 members" + ERR_END)}))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<List<GroupMemberResponse>>> addMembers(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @Valid @RequestBody GroupMembersRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.addMembers(groupId, request, principal.userId())));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "GROUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove a Group member", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: removes the member added by this user ID. Idempotent: succeeds, changing nothing, when the user is not a member added by ID; \
            one added by email is removed through `DELETE /api/groups/{groupId}/members?email=`. Polls the Group was already invited into keep their Invitees. \
            Returns the full member list.
            **Side effects**: the action is audit-logged.
            **Errors**:
            """ + NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the removal",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<List<GroupMemberResponse>>> removeMember(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @Parameter(description = "User ID of the member", required = true, example = "0199f2a1-2c5d-7f13-9b0e-7d4a1e3f6c82")
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.removeMember(groupId, userId, principal.userId())));
    }

    @DeleteMapping(value = "/{groupId}/members", params = "email")
    @Auditable(action = "REMOVE_MEMBER", resourceType = "GROUP", resourceIdIndex = 0)
    @CommonApiResponses
    @Operation(summary = "Remove an email Group member", description = """
            **Auth**: required (owner only)
            **Precondition**: none
            **Behavior**: removes the member added by this address (lower-cased and trimmed like sign-in does), whether or not it belongs to an account. \
            Idempotent: succeeds, changing nothing, when the address is not a member. Polls the Group was already invited into keep their Invitees. \
            Returns the full member list.
            **Side effects**: the action is audit-logged.
            **Errors**:
            """ + NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members after the removal",
            content = @Content(examples = @ExampleObject(name = "Members", value = EX_MEMBERS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Group does not exist or belongs to someone else (`group.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(name = "group.not_found", value = ERR_GROUP_NOT_FOUND)))
    public ResponseEntity<ApiResponse<List<GroupMemberResponse>>> removeEmailMember(
            @Parameter(description = "Group ID", required = true, example = "0199f2b0-7a1c-7e45-9b3d-2c8f6a4e1d07")
            @PathVariable String groupId,
            @Parameter(description = "Email address the member was added by", required = true, example = "dave@example.com")
            @RequestParam String email,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(groupService.removeEmailMember(groupId, email, principal.userId())));
    }
}

package com.vomatt.admin;

import com.vomatt.common.annotation.ApiError;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.users.CommunityService;
import com.vomatt.users.dto.CreateCommunityRequest;
import com.vomatt.users.dto.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/communities")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Community Account", description = "Community account creation (Admin)")
public class AdminCommunityController {

    private final CommunityService communityService;

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "COMMUNITY")
    @CommonApiResponses
    @Operation(summary = "Create a community account",
            description = """
                    **Auth**: admin (role ADMIN; 403 `common.forbidden` otherwise)
                    **Precondition**: the owner is an active account that is not a community account; the username is free
                    **Behavior**: creates a community account (a group's page: `displayName` is the group name, the logo is its avatar) \
                    with the given user as its first OWNER. It has no email or phone and can never sign in; members act as it with the \
                    `X-Act-As` header. Returns 201 with the account, `community` true
                    **Side effects**: the creation is audit-logged as `CREATE` on `COMMUNITY`, with the new account's id and username in the details
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "CreateCommunity", value = """
                    { "username": "sunrise_tower", "displayName": "Sunrise Tower Committee", "ownerUserId": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47" }""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Community account created",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "CreatedCommunity", value = """
                    { "success": true, "data": { "id": "0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d48", "username": "sunrise_tower",
                        "displayName": "Sunrise Tower Committee", "avatarUrl": null, "community": true },
                      "message": null, "errorCode": null, "error": null }""")))
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`username` not 3-50 characters of [a-z0-9_] or reserved, `displayName` blank or over 100 characters, `ownerUserId` missing")
    @ApiError(status = 400, key = MessageKey.COMMUNITY_MEMBER_INVALID, when = "the owner is suspended or is a community account")
    @ApiError(status = 404, key = MessageKey.USER_NOT_FOUND, when = "no user with `ownerUserId`")
    @ApiError(status = 409, key = MessageKey.USER_USERNAME_TAKEN, when = "the username is in use")
    public ResponseEntity<ApiResponse<UserDto>> createCommunity(@Valid @RequestBody CreateCommunityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(communityService.createCommunity(
                request.username(), request.displayName(), request.ownerUserId())));
    }
}

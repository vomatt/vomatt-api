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
import jakarta.validation.Valid;
import com.vomatt.common.audit.Auditable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

    @GetMapping("/me")
    @CommonApiResponses
    @Operation(summary = "Get my profile", description = "Get the complete profile of the authenticated user")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        MyProfileResponse profile = userService.getMyProfile(principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PatchMapping("/me/visibility")
    @CommonApiResponses
    @Operation(summary = "Update profile visibility", description = "Update field visibility settings for public profile")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> updateVisibility(
            @Valid @RequestBody UpdateVisibilityRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Map<String, Boolean> result = userService.updateVisibility(principal.userId(), request.visibility());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{username}")
    @PublicApiResponse
    @Operation(summary = "Get user public profile", description = "Get a user's public profile by username")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(
            @Parameter(description = "Username", required = true)
            @PathVariable String username) {
        UserProfileResponse profile = userService.getUserProfile(username, false);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PatchMapping("/me")
    @CommonApiResponses
    @Operation(summary = "Update my profile", description = "Update the authenticated user's display name and bio")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        UserProfileResponse profile = userService.updateMyProfile(principal.userId(), request);
        log.info("Profile updated for user: {}", principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @GetMapping("/search")
    @CommonApiResponses
    @Operation(summary = "Search users by username",
            description = "Case-insensitive partial match, ordered by username (cursor-paged)")
    public ResponseEntity<ApiResponse<CursorResponse<UserDto>>> searchUsers(
            @Parameter(description = "Username to search for", required = true)
            @RequestParam String username,
            @Parameter(description = "上一頁回傳的 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "每頁筆數（1–50，預設 20）") @RequestParam(required = false) Integer limit) {
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
    @Operation(summary = "Delete user account", description = "Delete a user account (users can only delete their own account, admins can delete any account)")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> deleteUser(
            @Parameter(description = "User ID", required = true)
            @PathVariable String userId,
            @AuthenticationPrincipal UserPrincipal principal) {

        userService.deleteUser(userId, principal);

        log.info("User {} deleted by {}", userId, principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

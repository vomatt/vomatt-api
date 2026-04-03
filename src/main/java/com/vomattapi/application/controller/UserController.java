package com.vomattapi.application.controller;

import com.vomattapi.application.dto.user.MyProfileResponse;
import com.vomattapi.application.dto.user.UpdateProfileRequest;
import com.vomattapi.application.dto.user.UpdateVisibilityRequest;
import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.common.ErrorType;
import com.vomattapi.application.dto.user.UserDto;
import com.vomattapi.application.dto.user.UserProfileResponse;
import com.vomattapi.application.exception.UnauthorizedOperationException;
import com.vomattapi.infrastructure.security.services.UserDetailsImpl;
import com.vomattapi.application.service.auth.RefreshTokenService;
import com.vomattapi.application.service.user.UserService;
import jakarta.validation.Valid;
import com.vomattapi.infrastructure.audit.Auditable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User management APIs")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Get my profile", description = "取得認證使用者的完整個人資料")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        MyProfileResponse profile = userService.getMyProfile(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PatchMapping("/me/visibility")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Update profile visibility", description = "更新公開個人資料的欄位顯示設定")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Visibility settings updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> updateVisibility(
            @Valid @RequestBody UpdateVisibilityRequest request,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Map<String, Boolean> result = userService.updateVisibility(userDetails.getId(), request.visibility());
        return ResponseEntity.ok(ApiResponse.success(result, "Visibility settings updated"));
    }

    @GetMapping("/{username}")
    @Operation(summary = "Get user public profile", description = "Get a user's public profile by username")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User profile retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(
            @Parameter(description = "Username", required = true)
            @PathVariable String username) {
        UserProfileResponse profile = userService.getUserProfile(username, false);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PatchMapping("/me")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Update my profile", description = "Update the authenticated user's display name and bio")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile updated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        UserProfileResponse profile = userService.updateMyProfile(userDetails.getId(), request);
        log.info("Profile updated for user: {}", userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(profile, "Profile updated successfully"));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Operation(summary = "Search users by username", description = "Search for users by username (case-insensitive, partial match)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Users found successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<Page<UserDto>>> searchUsers(
            @Parameter(description = "Username to search for", required = true)
            @RequestParam String username,
            @PageableDefault(size = 20) Pageable pageable) {
        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, "Username parameter is required"));
        }
        Page<UserDto> users = userService.searchUsersByUsername(username.trim(), pageable);
        log.info("User search completed for username: {}, found {} results", username, users.getTotalElements());
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    @Auditable(action = "DELETE", resourceType = "USER", resourceIdIndex = 0)
    @Operation(summary = "Delete user account", description = "Delete a user account (users can only delete their own account, admins can delete any account)")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User deleted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden - Users can only delete their own account"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @Parameter(description = "User ID", required = true)
            @PathVariable String userId,
            Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));

        if (!userDetails.getId().equals(userId) && !isAdmin) {
            log.warn("User {} attempted to delete user {} without permission", userDetails.getId(), userId);
            throw new UnauthorizedOperationException("delete", "user");
        }

        refreshTokenService.deleteByUserId(userId);
        userService.deleteUser(userId);

        log.info("User {} deleted by {}", userId, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("User account deleted successfully"));
    }
}

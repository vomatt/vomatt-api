package com.vomattapi.application.controller;

import com.vomattapi.application.dto.response.ApiResponse;
import com.vomattapi.application.dto.response.ErrorType;
import com.vomattapi.application.dto.response.UserDto;
import com.vomattapi.application.security.services.UserDetailsImpl;
import com.vomattapi.application.service.RefreshTokenService;
import com.vomattapi.application.service.UserService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User management APIs")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

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
        try {
            if (username == null || username.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION,
                                "Username parameter is required"));
            }

            Page<UserDto> users = userService.searchUsersByUsername(username.trim(), pageable);
            log.info("User search completed for username: {}, found {} results",
                    username, users.getTotalElements());
            return ResponseEntity.ok(ApiResponse.success(users));
        } catch (Exception e) {
            log.error("Failed to search users with username: {}", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(ErrorType.INTERNAL_ERROR, e.getMessage()));
        }
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
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

            // Check if user is trying to delete their own account or is an admin
            boolean isAdmin = userDetails.getAuthorities().stream()
                    .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));

            if (!userDetails.getId().equals(userId) && !isAdmin) {
                log.warn("User {} attempted to delete user {} without permission",
                        userDetails.getId(), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(ErrorType.UNAUTHORIZED_OPERATION,
                                "You can only delete your own account"));
            }

            // Delete refresh tokens for this user
            refreshTokenService.deleteByUserId(userId);

            // Delete user account
            userService.deleteUser(userId);

            log.info("User {} deleted by {}", userId, userDetails.getUsername());
            return ResponseEntity.ok(ApiResponse.success("User account deleted successfully"));
        } catch (RuntimeException e) {
            log.error("Failed to delete user: {}", userId, e);
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(ErrorType.USER_NOT_FOUND, e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(ErrorType.INTERNAL_ERROR, e.getMessage()));
        }
    }
}

package com.vomatt.admin.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Allowed values for the admin user APIs, so the console need not hard-code them")
public record AdminUserOptionsResponse(
        @Schema(description = "Roles accepted by PUT /api/admin/users/{userId}/roles and the `role` filter", example = "[\"user\", \"moderator\", \"admin\"]")
        List<String> roles,
        @Schema(description = "`channels` accepted by POST /api/admin/users/{userId}/unlock-login: `email` consumer email OTP, `phone` consumer phone OTP, `admin` admin console login",
                example = "[\"email\", \"phone\", \"admin\"]")
        List<String> unlockChannels,
        @Schema(description = "`clear` accepted by POST /api/admin/users/{userId}/unlock-login: `lock` the lockout and its doubling, `attempts` wrong-code counters, `cooldown` the resend wait",
                example = "[\"lock\", \"attempts\", \"cooldown\"]")
        List<String> unlockTargets
) {
}

package com.vomatt.users.dto;

import com.vomatt.common.security.RefreshTokenService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A device the user is signed in on")
public record SessionResponse(
        @Schema(description = "Names the sign-in; pass it to DELETE /api/users/me/sessions/{sessionId}. Not a credential",
                example = "0192f3c4-5d6e-7f80-9a1b-2c3d4e5f6a7b") String sessionId,
        @Schema(description = "Raw User-Agent of the latest sign-in or refresh, up to 512 characters; null for a session "
                + "signed in before devices were recorded that has not refreshed since (show as an unknown device)",
                example = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36",
                nullable = true) String userAgent,
        @Schema(description = "Sign-in time; null for a session signed in before it was recorded", nullable = true)
        Instant createdAt,
        @Schema(description = "Latest sign-in or token refresh, so accurate to about 15 minutes; null like userAgent",
                nullable = true) Instant lastSeenAt,
        @Schema(description = "Whether this is the session of the access token making the request") boolean current,
        @Schema(description = "Whether this is an admin console sign-in") boolean adminScope) {

    public static SessionResponse from(RefreshTokenService.RefreshTokenData session, String currentSessionId) {
        return new SessionResponse(session.sessionId(), session.userAgent(), session.createdAt(), session.lastSeenAt(),
                session.sessionId().equals(currentSessionId), session.grantsAdmin());
    }
}

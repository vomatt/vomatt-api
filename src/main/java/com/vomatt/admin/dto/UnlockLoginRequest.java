package com.vomatt.admin.dto;

import java.util.List;
import java.util.TreeSet;

import com.vomatt.common.audit.AuditDetails;
import com.vomatt.common.constant.LoginUnlock;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The audit log stores the scope in details, e.g. "channels=email,phone ip=* clear=attempts,lock". */
@Schema(description = "What to clear; every field is optional and an omitted field means all. "
        + "Allowed values are listed by GET /api/admin/users/options")
public record UnlockLoginRequest(
        @ArraySchema(arraySchema = @Schema(description = "Login channels to clear; omit for all", example = "[\"email\"]", nullable = true),
                schema = @Schema(allowableValues = {"email", "phone", "admin"}))
        List<@NotNull @Pattern(regexp = LoginUnlock.CHANNEL_PATTERN) String> channels,

        @Pattern(regexp = "[0-9A-Fa-f.:]{2,45}")
        @Schema(description = "Clear only this client IP, exactly as the server saw it (for example from the error report or logs); omit for every IP",
                example = "203.0.113.5", nullable = true)
        String ip,

        @ArraySchema(arraySchema = @Schema(description = "What to clear; omit for all", example = "[\"lock\", \"attempts\"]", nullable = true),
                schema = @Schema(allowableValues = {"lock", "attempts", "cooldown"}))
        List<@NotNull @Pattern(regexp = LoginUnlock.TARGET_PATTERN) String> clear
) implements AuditDetails {

    public List<String> channelsOrAll() {
        return channels == null || channels.isEmpty() ? LoginUnlock.CHANNELS : channels;
    }

    public List<String> clearOrAll() {
        return clear == null || clear.isEmpty() ? LoginUnlock.TARGETS : clear;
    }

    @Override
    public String auditDetails() {
        return "channels=" + String.join(",", new TreeSet<>(channelsOrAll())) + " ip=" + (ip == null ? "*" : ip)
                + " clear=" + String.join(",", new TreeSet<>(clearOrAll()));
    }
}

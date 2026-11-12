package com.vomatt.auth;

import com.vomatt.common.annotation.ApiError;
import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.auth.dto.PasskeyRegistrationVerifyRequest;
import com.vomatt.auth.dto.PasskeyResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/passkey")
@Tag(name = "Auth")
@RequiredArgsConstructor
public class PasskeyController {

    private static final String OK_REGISTER_OPTIONS = """
            {
              "success": true,
              "data": {
                "rp": {"id": "vomatt.com", "name": "Vomatt"},
                "user": {"id": "AZCosh_lJBh4kKvN7wEjRWeJ", "name": "user@example.com", "displayName": "Ming Wang"},
                "challenge": "Qm8AYkRgZ3B1c2hxS1d0X0Nmc2VhLWtPZ2V3UjN2cTQ",
                "pubKeyCredParams": [{"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}],
                "timeout": 300000,
                "excludeCredentials": [{"type": "public-key", "id": "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI", "transports": ["hybrid", "internal"]}],
                "authenticatorSelection": {"residentKey": "required", "requireResidentKey": true, "userVerification": "preferred"},
                "hints": ["client-device", "hybrid", "security-key"],
                "attestation": "none"
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String USER_NOT_FOUND = """
            {"success": false, "data": null, "message": "User not found", "errorCode": "user.not_found", "error": "User not found"}""";

    private static final String OK_PASSKEY = """
            {
              "success": true,
              "data": {"id": "0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70", "name": "iPhone", "createdAt": "2026-10-27T08:30:00Z", "lastUsedAt": null, "transports": ["hybrid", "internal"]},
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private final PasskeyService passkeyService;

    @PostMapping("/register/options")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_REGISTER_OPTIONS)))
    @Operation(summary = "Get passkey registration options",
            description = """
                    **Auth**: required (Bearer token)
                    **Precondition**: none; a user may register several passkeys
                    **Behavior**: returns WebAuthn creation options for the caller: pass `data` to `PublicKeyCredential.parseCreationOptionsFromJSON` \
                    and then `navigator.credentials.create({ publicKey })`, and send `credential.toJSON()` to `POST /api/auth/passkey/register/verify`. \
                    `rp.id` is always vomatt.com (the configured RP ID), so the passkey works on vomatt.com, admin.vomatt.com and the iOS app. \
                    The passkey must be discoverable (`residentKey: required`); `excludeCredentials` lists the caller's existing passkeys
                    **Side effects**: issues a one-time challenge that expires after 5 minutes; calling again issues another one and leaves earlier ones valid until they expire or are spent
                    """)
    @ApiError(status = 401, key = MessageKey.COMMON_UNAUTHORIZED, when = "no or invalid access token")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the account is suspended")
    @ApiError(status = 404, key = MessageKey.USER_NOT_FOUND, when = "the account no longer exists")
    public ResponseEntity<ApiResponse<PasskeyRegistrationOptionsResponse>> registrationOptions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.registrationOptions(principal.userId())));
    }

    @PostMapping("/register/verify")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_PASSKEY)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {
                      "credential": {
                        "id": "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI",
                        "rawId": "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI",
                        "type": "public-key",
                        "response": {
                          "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiUW04QSJ9",
                          "attestationObject": "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVjF",
                          "transports": ["internal", "hybrid"]
                        },
                        "authenticatorAttachment": "platform",
                        "clientExtensionResults": {}
                      },
                      "name": "iPhone"
                    }""")))
    @Operation(summary = "Register a passkey",
            description = """
                    **Auth**: required (Bearer token), the same user who got the options
                    **Precondition**: a challenge from `POST /api/auth/passkey/register/options`, at most 5 minutes old and not yet used
                    **Behavior**: send the created credential's `credential.toJSON()` as `credential` (iOS: the same fields from \
                    `ASAuthorizationPlatformPublicKeyCredentialRegistration`, base64url), with an optional `name` (at most 100 characters; blank is stored as null). \
                    The server checks the challenge, the origin (https://vomatt.com or https://admin.vomatt.com), the RP ID hash (vomatt.com), \
                    user presence and the key algorithm, then stores the passkey for the caller and returns it. \
                    Only a passkey registered with an admin console access token (from `POST /api/auth/admin/verify-otp` or the admin passkey sign-in) \
                    can sign in to the admin console; one registered with any other token signs in to the consumer apps only
                    **Side effects**: the challenge is spent whether or not verification succeeds, so a retry needs new options; a new passkey row
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_PASSKEY_INVALID, when = "the response is malformed or fails verification (wrong origin or RP ID, unknown, spent, expired or another user's challenge, unsupported algorithm); get new options and retry")
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`credential` or one of its required fields is missing, or `name` is too long")
    @ApiError(status = 401, key = MessageKey.COMMON_UNAUTHORIZED, when = "no or invalid access token")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the account is suspended")
    @ApiError(status = 404, key = MessageKey.USER_NOT_FOUND, when = "the account no longer exists")
    @ApiError(status = 409, key = MessageKey.COMMON_CONFLICT, when = "this passkey is already registered (to this or another account)")
    public ResponseEntity<ApiResponse<PasskeyResponse>> verifyRegistration(
            @Valid @RequestBody PasskeyRegistrationVerifyRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.verifyRegistration(principal.userId(), principal.grantsAdmin(), request)));
    }
}

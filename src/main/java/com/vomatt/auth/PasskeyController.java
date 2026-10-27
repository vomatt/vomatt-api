package com.vomatt.auth;

import com.vomatt.auth.dto.PasskeyRegistrationOptionsResponse;
import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final PasskeyService passkeyService;

    @PostMapping("/register/options")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_REGISTER_OPTIONS)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Account no longer exists (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = USER_NOT_FOUND)))
    @Operation(summary = "Get passkey registration options",
            description = """
                    **Auth**: required (Bearer token)
                    **Precondition**: none; a user may register several passkeys
                    **Behavior**: returns WebAuthn creation options for the caller: pass `data` to `PublicKeyCredential.parseCreationOptionsFromJSON` \
                    and then `navigator.credentials.create({ publicKey })`, and send `credential.toJSON()` to `POST /api/auth/passkey/register/verify`. \
                    `rp.id` is always vomatt.com (the configured RP ID), so the passkey works on vomatt.com, admin.vomatt.com and the iOS app. \
                    The passkey must be discoverable (`residentKey: required`); `excludeCredentials` lists the caller's existing passkeys
                    **Side effects**: issues a one-time challenge that expires after 5 minutes; calling again issues another one and leaves earlier ones valid until they expire or are spent
                    **Errors**:
                    - 401 `common.unauthorized`: no or invalid access token
                    - 404 `user.not_found`: the account no longer exists""")
    public ResponseEntity<ApiResponse<PasskeyRegistrationOptionsResponse>> registrationOptions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.registrationOptions(principal.userId())));
    }
}

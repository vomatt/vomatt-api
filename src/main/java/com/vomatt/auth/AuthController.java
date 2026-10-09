package com.vomatt.auth;

import com.vomatt.auth.dto.AppleAuthRequest;
import com.vomatt.auth.dto.AuthEmailExistsResponse;
import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.auth.dto.CheckEmailRequest;
import com.vomatt.auth.dto.GoogleAuthRequest;
import com.vomatt.auth.dto.LineAuthRequest;
import com.vomatt.auth.dto.PhoneSendOtpRequest;
import com.vomatt.auth.dto.PhoneVerifyOtpRequest;
import com.vomatt.auth.dto.RefreshRequest;
import com.vomatt.auth.dto.SendOtpRequest;
import com.vomatt.auth.dto.VerifyOtpRequest;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Authentication: email OTP, phone OTP, Google / LINE / Apple login, token refresh and logout. "
        + "See docs/frontend/auth-flow.md for the end-to-end flows.")
@RequiredArgsConstructor
public class AuthController {

    private static final String OK_RESULT = """
            {"success": true, "data": {"success": true}, "message": null, "errorCode": null, "error": null}""";

    private static final String OK_AUTH = """
            {
              "success": true,
              "data": {
                "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMTkwYThiMi0xMjM0LTc4OTAtYWJjZC1lZjAxMjM0NTY3ODkifQ.sig",
                "refreshToken": "8c7e3f4a-1234-5678-90ab-cdef12345678",
                "user": {
                  "id": "0190a8b2-1234-7890-abcd-ef0123456789",
                  "email": "user@example.com",
                  "username": "user_0427",
                  "displayName": "Ming Wang",
                  "roles": ["user"],
                  "avatarUrl": null,
                  "phoneNumber": null
                }
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String OK_REFRESH = """
            {
              "success": true,
              "data": {
                "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMTkwYThiMi0xMjM0LTc4OTAtYWJjZC1lZjAxMjM0NTY3ODkifQ.sig",
                "refreshToken": "2f9d1c6e-7b3a-4c58-9e10-a4b5c6d7e8f9",
                "user": null
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private final AuthService authService;

    @PostMapping("/send-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_RESULT)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "user@example.com", "checkRole": "false"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "`checkRole` is \"true\" and the email is not an existing admin account",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account does not have admin privileges", "errorCode": "auth.admin.forbidden", "error": "This account does not have admin privileges"}""")))
    @Operation(summary = "Send email OTP",
            description = """
                    **Auth**: public
                    **Precondition**: none; the same endpoint serves login and signup (the server picks which by whether the email is registered)
                    **Behavior**: generates a 6-digit OTP, stores it with a TTL (default 180 s) and emails it. The email is lower-cased and trimmed. \
                    If `checkRole` is "true", only an existing admin account may request an OTP (admin console login)
                    **Side effects**: sends an email; replaces any previous OTP for this address. A resend is refused during the cooldown (default 180 s)
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 400 `auth.otp.resend_cooldown`: an OTP was sent too recently; the message states the seconds left
                    - 400 `auth.email.send_failed`: the email provider rejected the message
                    - 403 `auth.admin.forbidden`: `checkRole` is "true" and the account is not an admin""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> sendOtp(@Valid @RequestBody SendOtpRequest req) {
        authService.sendOtp(req.email(), null, null, Boolean.parseBoolean(req.checkRole()));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/verify-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "user@example.com", "code": "123456", "checkRole": "false"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "OTP missing/expired, wrong, or too many wrong attempts",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Invalid verification code", "errorCode": "auth.invalid_otp", "error": "Invalid verification code"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`) or not an admin when `checkRole` is \"true\" (`auth.admin.forbidden`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @Operation(summary = "Verify email OTP and sign in",
            description = """
                    **Auth**: public
                    **Precondition**: an OTP was sent to this email via `POST /api/auth/send-otp` and has not expired
                    **Behavior**: consumes the OTP and returns an access token and refresh token. Creates the user if the email is new (signup), \
                    otherwise signs the existing user in. If `checkRole` is "true", the account must already exist and be an admin, and this is checked before the OTP is consumed
                    **Side effects**: the OTP is consumed on success; a new user is created and a welcome email sent on signup; `lastLoginAt` is updated. \
                    After 5 wrong codes the OTP is deleted and a new one must be requested
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 401 `auth.otp.invalid_or_expired`: no OTP exists for this email (never sent, expired, or already used)
                    - 401 `auth.invalid_otp`: the code is wrong
                    - 401 `auth.otp.too_many_attempts`: 5th wrong code; the OTP is discarded
                    - 403 `auth.admin.forbidden`: `checkRole` is "true" and the account is not an existing admin
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        AuthResponse response = authService.verifyOtp(req.email(), null, req.code(), Boolean.parseBoolean(req.checkRole()));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/check-email")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success": true, "data": {"exists": true}, "message": null, "errorCode": null, "error": null}""")))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "user@example.com"}""")))
    @Operation(summary = "Check whether an email is registered",
            description = """
                    **Auth**: public
                    **Precondition**: none
                    **Behavior**: returns whether a user with this email exists (case-insensitive). Lets the client choose between login and signup wording
                    **Side effects**: none
                    **Errors**:
                    - 400 `common.validation_failed`: `email` is blank or not a valid email""")
    public ResponseEntity<ApiResponse<AuthEmailExistsResponse>> checkEmail(@Valid @RequestBody CheckEmailRequest req) {
        boolean exists = authService.checkEmailExists(req.email());
        return ResponseEntity.ok(ApiResponse.ok(new AuthEmailExistsResponse(exists)));
    }

    @PostMapping("/phone/send-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_RESULT)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"phone": "0912345678", "type": "login"}""")))
    @Operation(summary = "Send phone OTP",
            description = """
                    **Auth**: public
                    **Precondition**: for `type` "login" (the default) the phone number must already be registered
                    **Behavior**: generates a 6-digit OTP, stores it with a TTL (default 180 s) and sends it by SMS. `type` is "login" or "signup"
                    **Side effects**: sends an SMS; replaces any previous OTP for this number. A resend is refused during the cooldown (default 180 s)
                    **Errors**:
                    - 400 `auth.otp.resend_cooldown`: an OTP was sent too recently; the message states the seconds left
                    - 400 `auth.phone.not_registered`: `type` is "login" and the number is not registered
                    - 400 `auth.sms.send_failed`: the SMS provider rejected the message""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> phoneSendOtp(@Valid @RequestBody PhoneSendOtpRequest req) {
        authService.sendOtp(null, req.phone(), req.type(), false);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/phone/verify-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"phone": "0912345678", "code": "123456"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "OTP missing/expired, wrong, or too many wrong attempts",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Invalid verification code", "errorCode": "auth.invalid_otp", "error": "Invalid verification code"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @Operation(summary = "Verify phone OTP and sign in",
            description = """
                    **Auth**: public
                    **Precondition**: an OTP was sent to this number via `POST /api/auth/phone/send-otp` and has not expired
                    **Behavior**: consumes the OTP and returns an access token and refresh token. Creates the user if the number is new, otherwise signs the existing user in
                    **Side effects**: the OTP is consumed on success; a new user is created if needed; `lastLoginAt` is updated. After 5 wrong codes the OTP is deleted and a new one must be requested
                    **Errors**:
                    - 401 `auth.otp.invalid_or_expired`: no OTP exists for this number (never sent, expired, or already used)
                    - 401 `auth.invalid_otp`: the code is wrong
                    - 401 `auth.otp.too_many_attempts`: 5th wrong code; the OTP is discarded
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> phoneVerifyOtp(@Valid @RequestBody PhoneVerifyOtpRequest req) {
        AuthResponse response = authService.verifyOtp(null, req.phone(), req.code(), false);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/google")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"idToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij..."}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Google ID token rejected, or its email is not verified",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This OAuth account's email is not verified; cannot sign in or merge an existing account", "errorCode": "auth.email_not_verified", "error": "This OAuth account's email is not verified; cannot sign in or merge an existing account"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @Operation(summary = "Sign in with Google",
            description = """
                    **Auth**: public
                    **Precondition**: the client obtained a Google ID token (Google Sign-In)
                    **Behavior**: validates the ID token with Google and checks its audience. Signs in the user with that email, creating the user if new. \
                    An existing account with the same email is reused only when Google reports the email as verified
                    **Side effects**: creates a user on first login; sets the avatar from the Google picture when the user has none; `lastLoginAt` is updated
                    **Errors**:
                    - 400 `auth.google.not_configured`: Google login is not configured on the server
                    - 401 `auth.google.no_email`: the token is invalid or carries no email
                    - 401 `auth.google.invalid_audience`: the token was issued for a different client ID
                    - 401 `auth.email_not_verified`: Google reports the email as unverified
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(@Valid @RequestBody GoogleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.googleLogin(req.idToken())));
    }

    @PostMapping("/line")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"code": "abc123def456", "redirectUri": "https://app.example.com/auth/line/callback"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "LINE authorization code could not be exchanged, or the LINE profile could not be read",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "LINE token exchange failed", "errorCode": "auth.line.token_exchange_failed", "error": "LINE token exchange failed"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @Operation(summary = "Sign in with LINE",
            description = """
                    **Auth**: public
                    **Precondition**: the client completed LINE Login and holds the authorization `code` and the `redirectUri` it used
                    **Behavior**: exchanges the code with LINE, reads the LINE profile and signs in the matching user, creating it if new. \
                    If LINE provides no email, a synthetic `line_<userId>@line.oauth` email is used, so `user.email` in the response is never null for LINE logins
                    **Side effects**: creates a user on first login; sets the avatar from the LINE picture when the user has none; `lastLoginAt` is updated
                    **Errors**:
                    - 400 `auth.line.not_configured`: LINE login is not configured on the server
                    - 401 `auth.line.token_exchange_failed`: LINE did not return an access token for this code
                    - 401 `auth.line.profile_failed`: the LINE profile could not be read
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> lineLogin(@Valid @RequestBody LineAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.lineLogin(req.code(), req.redirectUri())));
    }

    @PostMapping("/apple")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"idToken": "eyJraWQiOiJZdXl1eVoxIiwiYWxnIjoiUlMyNTYifQ...", "fullName": "Ming Wang"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Apple identity token rejected (malformed, bad signature, wrong issuer/audience, expired, no email, or unverified email)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Apple verification failed: invalid signature", "errorCode": "auth.apple.invalid_signature", "error": "Apple verification failed: invalid signature"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Apple's key endpoint is unreachable; safe to retry (`common.service_unavailable`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF)))
    @Operation(summary = "Sign in with Apple",
            description = """
                    **Auth**: public
                    **Precondition**: the client obtained an Apple identity token (Sign in with Apple)
                    **Behavior**: verifies the token signature against Apple's public keys, then its issuer, audience and expiry. Signs in the user with the token's email, creating it if new. \
                    `fullName` is used only when a new user is created. An existing account with the same email is reused only when Apple reports the email as verified
                    **Side effects**: creates a user on first login; `lastLoginAt` is updated
                    **Errors**:
                    - 401 `auth.apple.invalid_token`: the token is malformed or has no key ID
                    - 401 `auth.apple.key_not_found`: no Apple public key matches the token
                    - 401 `auth.apple.invalid_signature`: the signature does not verify
                    - 401 `auth.apple.invalid_issuer`: the issuer is not Apple
                    - 401 `auth.apple.invalid_audience`: the token was issued for a different client ID
                    - 401 `auth.apple.token_expired`: the token has expired
                    - 401 `auth.apple.no_email`: the token carries no email
                    - 401 `auth.email_not_verified`: Apple reports the email as unverified
                    - 401 `auth.apple.login_failed`: any other failure while processing the token
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)
                    - 503 `common.service_unavailable`: Apple's key endpoint failed or returned an unusable response""")
    public ResponseEntity<ApiResponse<AuthResponse>> appleLogin(@Valid @RequestBody AppleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.appleLogin(req.idToken(), req.fullName())));
    }

    @PostMapping("/refresh")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_REFRESH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"refreshToken": "8c7e3f4a-1234-5678-90ab-cdef12345678"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Refresh token invalid/expired/revoked (`auth.refresh_token.invalid`), or reuse of an already-rotated token detected (`auth.suspicious_login`); either way the client must sign in again",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Security alert: suspicious login detected. All sessions have been terminated, please log in again", "errorCode": "auth.suspicious_login", "error": "Security alert: suspicious login detected. All sessions have been terminated, please log in again"}""")))
    @Operation(summary = "Refresh access token",
            description = """
                    **Auth**: public (authenticated by the refresh token in the body, not by the Authorization header)
                    **Precondition**: a refresh token from a previous login or refresh
                    **Behavior**: rotates the refresh token: the old one is spent and a new access token and refresh token are returned. \
                    The response `user` is null; keep the user info from login. Roles are re-read from the database, so a role change takes effect on refresh. \
                    A second call with the same old token within the grace window (default 10 s) returns the same new pair instead of failing, so concurrent refreshes are safe
                    **Side effects**: the old refresh token is spent. If a spent token is presented again after the grace window, all of the user's refresh tokens are revoked. \
                    If the user is missing or suspended, all their refresh tokens are revoked too
                    **Errors**:
                    - 401 `auth.refresh_token.invalid`: the token is unknown, expired, revoked, or its user is missing or suspended
                    - 401 `auth.suspicious_login`: a spent token was reused; every session of the user is now terminated""")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req.refreshToken())));
    }

    @PostMapping("/logout")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_RESULT)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"refreshToken": "8c7e3f4a-1234-5678-90ab-cdef12345678"}""")))
    @Operation(summary = "Sign out",
            description = """
                    **Auth**: public (identified by the refresh token in the body)
                    **Precondition**: none; an unknown or already-revoked token is not an error
                    **Behavior**: revokes the given refresh token. Other devices' refresh tokens stay valid
                    **Side effects**: the refresh token can no longer be refreshed. The access token is stateless and stays valid until it expires (default 1 hour), so the client must discard it
                    **Errors**:
                    - 400 `common.validation_failed`: `refreshToken` is blank""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

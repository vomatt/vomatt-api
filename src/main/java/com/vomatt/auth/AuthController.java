package com.vomatt.auth;

import com.vomatt.auth.dto.AppleAuthRequest;
import com.vomatt.auth.dto.AuthResponse;
import com.vomatt.auth.dto.GoogleAuthRequest;
import com.vomatt.auth.dto.LineAuthRequest;
import com.vomatt.auth.dto.PasskeyLoginOptionsResponse;
import com.vomatt.auth.dto.PasskeyLoginVerifyRequest;
import com.vomatt.auth.dto.PhoneSendOtpRequest;
import com.vomatt.auth.dto.PhoneVerifyOtpRequest;
import com.vomatt.auth.dto.RefreshRequest;
import com.vomatt.auth.dto.SendOtpRequest;
import com.vomatt.auth.dto.VerifyOtpRequest;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.util.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Authentication: email OTP, phone OTP, Google / LINE / Apple login, admin console login, token refresh and logout. "
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
                  "avatarUrl": "bot:boba",
                  "phoneNumber": null
                },
                "isNewUser": false
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
                "user": null,
                "isNewUser": null
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String OK_ADMIN_AUTH = """
            {
              "success": true,
              "data": {
                "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMTkwYThiMi0xMjM0LTc4OTAtYWJjZC1lZjAxMjM0NTY3ODkifQ.sig",
                "refreshToken": "8c7e3f4a-1234-5678-90ab-cdef12345678",
                "user": {
                  "id": "0190a8b2-1234-7890-abcd-ef0123456789",
                  "email": "admin@example.com",
                  "username": "admin",
                  "displayName": "Admin",
                  "roles": ["user", "admin"],
                  "avatarUrl": "bot:boba",
                  "phoneNumber": null
                },
                "isNewUser": false
              },
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String ADMIN_FORBIDDEN = """
            {"success": false, "data": null, "message": "This account does not have admin privileges", "errorCode": "auth.admin.forbidden", "error": "This account does not have admin privileges"}""";

    private static final String OK_PASSKEY_LOGIN_OPTIONS = """
            {
              "success": true,
              "data": {"challenge": "Qm8AYkRgZ3B1c2hxS1d0X0Nmc2VhLWtPZ2V3UjN2cTQ", "timeout": 300000, "rpId": "vomatt.com",
                       "allowCredentials": [], "userVerification": "preferred"},
              "message": null,
              "errorCode": null,
              "error": null
            }""";

    private static final String PASSKEY_LOGIN_BODY = """
                    {
                      "credential": {
                        "id": "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI",
                        "rawId": "RBBLHcqGtSGftlCq_9zzjSpKZIdZvgpor7plCJ4BJbI",
                        "type": "public-key",
                        "response": {
                          "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiUW04QSJ9",
                          "authenticatorData": "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAQ",
                          "signature": "MEUCIQDq4f0Q3E4lhVb3hYQm9TLu",
                          "userHandle": "AZCosh_lJBh4kKvN7wEjRWeJ"
                        },
                        "authenticatorAttachment": "platform",
                        "clientExtensionResults": {}
                      }
                    }""";

    private static final String PASSKEY_INVALID = """
            {"success": false, "data": null, "message": "Passkey verification failed. Please try again", "errorCode": "auth.passkey.invalid", "error": "Passkey verification failed. Please try again"}""";

    private static final String ACCOUNT_BANNED = """
            {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""";

    private final AuthService authService;
    private final PasskeyService passkeyService;

    @PostMapping("/send-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_RESULT)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "user@example.com"}""")))
    @Operation(summary = "Send email OTP",
            description = """
                    **Auth**: public
                    **Precondition**: none; the same endpoint serves login and signup (the server picks which by whether the email is registered)
                    **Behavior**: generates a 6-digit OTP, stores it with a TTL (default 180 s) and emails it. The email is lower-cased and trimmed; a Gmail address (gmail.com / googlemail.com) also loses its +tag and dots and moves to gmail.com. \
                    The admin console signs in with `POST /api/auth/admin/send-otp` instead
                    **Side effects**: sends an email; replaces any previous OTP for this address. A resend is refused during the cooldown (default 180 s), and at most 20 OTPs are sent to one address per UTC day
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 400 `auth.otp.resend_cooldown`: an OTP was sent too recently; the message states the seconds left
                    - 400 `auth.email.send_failed`: the email provider rejected the message; the OTP is dropped and a resend is allowed after 30 s
                    - 401 `auth.otp.too_many_attempts`: this email is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected
                    - 429 `auth.otp.daily_limit`: 20 OTPs were already sent to this address today (UTC)""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> sendOtp(@Valid @RequestBody SendOtpRequest req, HttpServletRequest httpRequest) {
        authService.sendOtp(req.email(), null, null, ClientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/verify-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "user@example.com", "code": "123456"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "OTP missing/expired, wrong, or too many wrong attempts",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Invalid verification code", "errorCode": "auth.invalid_otp", "error": "Invalid verification code"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @Operation(summary = "Verify email OTP and sign in",
            description = """
                    **Auth**: public
                    **Precondition**: an OTP was sent to this email via `POST /api/auth/send-otp` and has not expired
                    **Behavior**: consumes the OTP and returns an access token and refresh token. Creates the user if the email is new (signup), \
                    otherwise signs the existing user in; `isNewUser` tells which, so the client needs no separate registration check. The tokens never grant the admin role, even for an admin account: the admin console signs in with `POST /api/auth/admin/verify-otp`
                    **Side effects**: the OTP is consumed on success; a new user (with a random bot avatar) is created and a welcome email sent on signup; `lastLoginAt` is updated. \
                    Wrong codes are counted per email and client IP; resending does not reset the count. \
                    The 3rd wrong code locks this email for the caller's IP for 5 minutes, doubling on each further lockout (10, 20, 40 minutes...) up to 24 hours; \
                    the doubling is forgotten 24 hours after the last lock ends, and a successful sign-in from that IP resets it. \
                    While locked, sending or verifying an OTP from that IP is refused; other IPs are not affected and the OTP stays valid. \
                    One OTP allows 10 guesses in total across all IPs; after the 10th wrong guess it no longer verifies and a new OTP can be requested only after 15 minutes
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 401 `auth.otp.invalid_or_expired`: no OTP exists for this email (never sent, expired, already used, or its 10 guesses are used up)
                    - 401 `auth.invalid_otp`: the code is wrong
                    - 401 `auth.otp.too_many_attempts`: 3rd wrong code, or this identifier is locked for the caller's IP (even a correct code is refused until the lock ends)
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req, HttpServletRequest httpRequest) {
        AuthResponse response = authService.verifyOtp(req.email(), null, req.code(), ClientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/admin/send-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_RESULT)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "admin@example.com"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "The email is not an existing admin account (`auth.admin.forbidden`) or the admin is suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = ADMIN_FORBIDDEN)))
    @Operation(summary = "Send admin console email OTP",
            description = """
                    **Auth**: public
                    **Precondition**: the email belongs to an existing, active account holding the admin role
                    **Behavior**: like `POST /api/auth/send-otp` (6-digit OTP, TTL default 180 s, same email normalization), but only for admin accounts. \
                    Its OTPs, wrong-code counters, lockouts and resend cooldown are separate from the consumer login, so a code from `send-otp` does not work here and vice versa
                    **Side effects**: sends an email; replaces any previous admin console OTP for this address. A resend is refused during the cooldown (default 180 s), and at most 20 OTPs are sent to one address per UTC day
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 400 `auth.otp.resend_cooldown`: an OTP was sent too recently; the message states the seconds left
                    - 400 `auth.email.send_failed`: the email provider rejected the message; the OTP is dropped and a resend is allowed after 30 s
                    - 401 `auth.otp.too_many_attempts`: this email is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected
                    - 429 `auth.otp.daily_limit`: 20 OTPs were already sent to this address today (UTC)
                    - 403 `auth.admin.forbidden`: the email is not an existing admin account
                    - 403 `auth.account_banned`: the admin is suspended (`active=false`); no OTP is sent""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> adminSendOtp(@Valid @RequestBody SendOtpRequest req, HttpServletRequest httpRequest) {
        authService.sendAdminOtp(req.email(), ClientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/admin/verify-otp")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_ADMIN_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"email": "admin@example.com", "code": "123456"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "OTP missing/expired, wrong, or too many wrong attempts",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Invalid verification code", "errorCode": "auth.invalid_otp", "error": "Invalid verification code"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Not an existing admin account (`auth.admin.forbidden`) or account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = ADMIN_FORBIDDEN)))
    @Operation(summary = "Verify admin console email OTP and sign in",
            description = """
                    **Auth**: public
                    **Precondition**: an OTP was sent to this email via `POST /api/auth/admin/send-otp` and has not expired
                    **Behavior**: the only sign-in whose tokens grant the admin role (`/api/admin/**`). Checks the account is an existing admin before consuming the OTP, then returns an access token and refresh token; \
                    refreshing them keeps the admin scope. Never creates an account. Wrong-code counting, lockouts and the 10-guess budget work as in `POST /api/auth/verify-otp`, separately from it
                    **Side effects**: the OTP is consumed on success; `lastLoginAt` is updated
                    **Errors**:
                    - 400 `auth.identifier.required`: `email` is missing or blank
                    - 401 `auth.otp.invalid_or_expired`: no admin console OTP exists for this email (never sent, expired, already used, or its 10 guesses are used up)
                    - 401 `auth.invalid_otp`: the code is wrong
                    - 401 `auth.otp.too_many_attempts`: 3rd wrong code, or this email is locked for the caller's IP (even a correct code is refused until the lock ends)
                    - 403 `auth.admin.forbidden`: the email is not an existing admin account
                    - 403 `auth.account_banned`: the admin is suspended (`active=false`); checked before the OTP is consumed""")
    public ResponseEntity<ApiResponse<AuthResponse>> adminVerifyOtp(@Valid @RequestBody VerifyOtpRequest req, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyAdminOtp(req.email(), req.code(), ClientIpResolver.resolve(httpRequest))));
    }

    @PostMapping("/passkey/login/options")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_PASSKEY_LOGIN_OPTIONS)))
    @Operation(summary = "Get passkey sign-in options",
            description = """
                    **Auth**: public
                    **Precondition**: none; the user does not enter an email or username (usernameless sign-in)
                    **Behavior**: returns WebAuthn request options: pass `data` to `PublicKeyCredential.parseRequestOptionsFromJSON` \
                    and then `navigator.credentials.get({ publicKey })`, and send `credential.toJSON()` to `POST /api/auth/passkey/login/verify` \
                    (or `POST /api/auth/admin/passkey/login/verify` in the admin console). `allowCredentials` is always empty, so the user picks any passkey for vomatt.com
                    **Side effects**: issues a one-time challenge that expires after 5 minutes
                    **Errors**:
                    - 429 `common.rate_limited`: too many auth requests from this IP""")
    public ResponseEntity<ApiResponse<PasskeyLoginOptionsResponse>> passkeyLoginOptions() {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.loginOptions()));
    }

    @PostMapping("/passkey/login/verify")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = PASSKEY_LOGIN_BODY)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "The passkey sign-in was not accepted (`auth.passkey.invalid`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = PASSKEY_INVALID)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = ACCOUNT_BANNED)))
    @Operation(summary = "Sign in with a passkey",
            description = """
                    **Auth**: public
                    **Precondition**: a challenge from `POST /api/auth/passkey/login/options`, at most 5 minutes old and not yet used, and a passkey registered with `POST /api/auth/passkey/register/verify`
                    **Behavior**: send `credential.toJSON()` from `navigator.credentials.get()` as `credential` (iOS: the same fields from \
                    `ASAuthorizationPlatformPublicKeyCredentialAssertion`, base64url). The server checks the challenge, the origin, the RP ID, \
                    user presence and the signature against the stored passkey, and signs its owner in: the response has the same shape as `POST /api/auth/verify-otp` \
                    (`isNewUser` is always false). The tokens never grant the admin role, even for an admin; the admin console uses `POST /api/auth/admin/passkey/login/verify`
                    **Side effects**: the challenge is spent whether or not the sign-in succeeds; the passkey's sign count and last use, and the user's `lastLoginAt`, are updated. \
                    A sign count that did not increase is logged, not rejected
                    **Errors**:
                    - 400 `common.validation_failed`: `credential` or one of its required fields is missing
                    - 401 `auth.passkey.invalid`: the response is malformed or fails verification (unknown passkey, wrong signature, origin or RP ID, unknown, spent or expired challenge); get new options and retry
                    - 403 `auth.account_banned`: the account is suspended
                    - 429 `common.rate_limited`: too many auth requests from this IP""")
    public ResponseEntity<ApiResponse<AuthResponse>> passkeyLoginVerify(@Valid @RequestBody PasskeyLoginVerifyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyPasskey(req)));
    }


    @PostMapping("/admin/passkey/login/verify")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_ADMIN_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = PASSKEY_LOGIN_BODY)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "The passkey sign-in was not accepted (`auth.passkey.invalid`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = PASSKEY_INVALID)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Not an admin account (`auth.admin.forbidden`) or account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = ADMIN_FORBIDDEN)))
    @Operation(summary = "Sign in to the admin console with a passkey",
            description = """
                    **Auth**: public
                    **Precondition**: a challenge from `POST /api/auth/passkey/login/options` (shared with the consumer sign-in), at most 5 minutes old and not yet used
                    **Behavior**: the passkey counterpart of `POST /api/auth/admin/verify-otp`: verifies the passkey as `POST /api/auth/passkey/login/verify` does, \
                    then requires the owner to be an active admin and returns tokens that grant the admin role (`/api/admin/**`); refreshing them keeps the admin scope
                    **Side effects**: the challenge is spent whether or not the sign-in succeeds, also when the owner is not an admin; on success the passkey's sign count and last use, and `lastLoginAt`, are updated
                    **Errors**:
                    - 400 `common.validation_failed`: `credential` or one of its required fields is missing
                    - 401 `auth.passkey.invalid`: the response is malformed or fails verification; get new options and retry
                    - 403 `auth.admin.forbidden`: the passkey's owner is not an admin (same response as `POST /api/auth/admin/verify-otp`)
                    - 403 `auth.account_banned`: the admin is suspended
                    - 429 `common.rate_limited`: too many auth requests from this IP""")
    public ResponseEntity<ApiResponse<AuthResponse>> adminPasskeyLoginVerify(@Valid @RequestBody PasskeyLoginVerifyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyAdminPasskey(req)));
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
                    **Behavior**: normalizes the number to E.164 (`0912345678` and `+886912345678` are the same number), generates a 6-digit OTP, stores it with a TTL (default 180 s) and sends it by SMS. `type` is "login" or "signup"
                    **Side effects**: sends an SMS; replaces any previous OTP for this number. A resend is refused during the cooldown (default 180 s), and at most 5 OTPs are sent to one number per UTC day
                    **Errors**:
                    - 400 `auth.phone.invalid`: the number is neither a Taiwan mobile number (`09` + 8 digits) nor `+` country code and number (8-15 digits)
                    - 400 `auth.otp.resend_cooldown`: an OTP was sent too recently; the message states the seconds left
                    - 400 `auth.phone.not_registered`: `type` is "login" and the number is not registered
                    - 400 `auth.sms.send_failed`: the SMS provider rejected the message; the OTP is dropped and a resend is allowed after 30 s
                    - 401 `auth.otp.too_many_attempts`: this number is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected
                    - 429 `auth.otp.daily_limit`: 5 OTPs were already sent to this number today (UTC)""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> phoneSendOtp(@Valid @RequestBody PhoneSendOtpRequest req, HttpServletRequest httpRequest) {
        authService.sendOtp(null, req.phone(), req.type(), ClientIpResolver.resolve(httpRequest));
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
                    **Behavior**: normalizes the number to E.164 like send-otp, so an OTP sent to `0912345678` verifies as `+886912345678`. Consumes the OTP and returns an access token and refresh token. Creates the user if the number is new, otherwise signs the existing user in
                    **Side effects**: the OTP is consumed on success; a new user (with a random bot avatar) is created if needed; `lastLoginAt` is updated. Wrong codes are counted per number and client IP; resending does not reset the count. \
                    The 3rd wrong code locks this number for the caller's IP for 5 minutes, doubling on each further lockout (10, 20, 40 minutes...) up to 24 hours; \
                    the doubling is forgotten 24 hours after the last lock ends, and a successful sign-in from that IP resets it. \
                    While locked, sending or verifying an OTP from that IP is refused; other IPs are not affected and the OTP stays valid. \
                    One OTP allows 10 guesses in total across all IPs; after the 10th wrong guess it no longer verifies and a new OTP can be requested only after 15 minutes
                    **Errors**:
                    - 400 `auth.phone.invalid`: the number is not in a supported format (see send-otp)
                    - 401 `auth.otp.invalid_or_expired`: no OTP exists for this number (never sent, expired, already used, or its 10 guesses are used up)
                    - 401 `auth.invalid_otp`: the code is wrong
                    - 401 `auth.otp.too_many_attempts`: 3rd wrong code, or this identifier is locked for the caller's IP (even a correct code is refused until the lock ends)
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)""")
    public ResponseEntity<ApiResponse<AuthResponse>> phoneVerifyOtp(@Valid @RequestBody PhoneVerifyOtpRequest req, HttpServletRequest httpRequest) {
        AuthResponse response = authService.verifyOtp(null, req.phone(), req.code(), ClientIpResolver.resolve(httpRequest));
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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Google's key endpoint is unreachable; safe to retry (`common.service_unavailable`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Service temporarily unavailable, please try again later", "errorCode": "common.service_unavailable", "error": "Service temporarily unavailable, please try again later"}""")))
    @Operation(summary = "Sign in with Google",
            description = """
                    **Auth**: public
                    **Precondition**: the client obtained a Google ID token (Google Sign-In)
                    **Behavior**: verifies the ID token signature against Google's public keys, then its issuer, audience and expiry. Each ID token signs in once; presenting it again is refused. \
                    Signs in the user with that email, creating the user if new. An existing account with the same email is reused only when Google reports the email as verified
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated; the token is marked used for 15 minutes
                    **Errors**:
                    - 400 `auth.google.not_configured`: Google login is not configured on the server
                    - 401 `auth.google.no_email`: the token is invalid (malformed, bad signature, wrong issuer, expired) or carries no email
                    - 401 `auth.google.invalid_audience`: the token was issued for a different client ID
                    - 401 `auth.oauth.token_replayed`: this ID token was already used to sign in
                    - 401 `auth.email_not_verified`: Google reports the email as unverified
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)
                    - 503 `common.service_unavailable`: Google's key endpoint is unreachable""")
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
            description = "LINE rejected the code or the access token, rejected the id_token or returned an unusable one (`auth.line.token_exchange_failed`), or the LINE profile could not be read (`auth.line.profile_failed`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "LINE token exchange failed", "errorCode": "auth.line.token_exchange_failed", "error": "LINE token exchange failed"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended (`auth.account_banned`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "This account has been banned and cannot sign in", "errorCode": "auth.account_banned", "error": "This account has been banned and cannot sign in"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "LINE failed (5xx), rate limited (429 / 408), did not answer in time, or returned an empty or malformed id_token verify body (`common.service_unavailable`). The authorization code may already be spent, so restart LINE Login for a new code instead of resending this request",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Service temporarily unavailable, please try again later", "errorCode": "common.service_unavailable", "error": "Service temporarily unavailable, please try again later"}""")))
    @Operation(summary = "Sign in with LINE",
            description = """
                    **Auth**: public
                    **Precondition**: the client completed LINE Login and holds the authorization `code` and the `redirectUri` it used
                    **Behavior**: exchanges the code with LINE, reads the LINE profile and signs in the matching user, creating it if new. \
                    When LINE returns an id_token, it is verified with LINE and the login fails rather than proceed without that result. \
                    A synthetic `line_<userId>@line.oauth` email is used only when LINE returns no id_token, or verifies it but returns no email \
                    (the user granted no email scope), so `user.email` in the response is never null for LINE logins
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated
                    **Errors**:
                    - 400 `auth.line.not_configured`: LINE login is not configured on the server
                    - 401 `auth.line.token_exchange_failed`: LINE rejected the code or the id_token (4xx), returned no access token or an unusable id_token, \
                    or the id_token belongs to another LINE user than the profile
                    - 401 `auth.line.profile_failed`: LINE rejected the access token (4xx) or returned no profile
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)
                    - 503 `common.service_unavailable`: LINE's token, profile or id_token verify endpoint failed (5xx), \
                    rate limited (429 / 408), did not answer in time or returned an empty or malformed verify body. The code is single-use and may already be spent: restart LINE Login for a new code""")
    public ResponseEntity<ApiResponse<AuthResponse>> lineLogin(@Valid @RequestBody LineAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.lineLogin(req.code(), req.redirectUri())));
    }

    @PostMapping("/apple")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"idToken": "eyJraWQiOiJZdXl1eVoxIiwiYWxnIjoiUlMyNTYifQ...", "fullName": "Ming Wang", "nonce": "f3a9c2b4e1d8a7c6b5e4d3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2"}""")))
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
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Service temporarily unavailable, please try again later", "errorCode": "common.service_unavailable", "error": "Service temporarily unavailable, please try again later"}""")))
    @Operation(summary = "Sign in with Apple",
            description = """
                    **Auth**: public
                    **Precondition**: the client generated a random `nonce`, passed its SHA-256 hex to Apple when starting Sign in with Apple, and obtained an identity token
                    **Behavior**: verifies the token signature against Apple's public keys, then its issuer, audience, expiry and that its `nonce` claim equals SHA-256 of the given `nonce`. \
                    Each identity token signs in once; presenting it again is refused. Signs in the user with the token's email, creating it if new. \
                    `fullName` is used only when a new user is created. An existing account with the same email is reused only when Apple reports the email as verified
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated; the token is marked used for 15 minutes
                    **Errors**:
                    - 400 `auth.apple.not_configured`: Apple login is not configured on the server
                    - 401 `auth.apple.invalid_token`: the token is malformed or has no key ID
                    - 401 `auth.apple.key_not_found`: no Apple public key matches the token
                    - 401 `auth.apple.invalid_signature`: the signature does not verify
                    - 401 `auth.apple.invalid_issuer`: the issuer is not Apple
                    - 401 `auth.apple.invalid_audience`: the token was issued for a different client ID
                    - 401 `auth.apple.token_expired`: the token has expired
                    - 401 `auth.apple.invalid_nonce`: the token's `nonce` claim is missing or is not SHA-256 of the given `nonce`
                    - 401 `auth.oauth.token_replayed`: this identity token was already used to sign in
                    - 401 `auth.apple.no_email`: the token carries no email
                    - 401 `auth.email_not_verified`: Apple reports the email as unverified
                    - 401 `auth.apple.login_failed`: any other failure while processing the token
                    - 403 `auth.account_banned`: the user is suspended (`active=false`)
                    - 503 `common.service_unavailable`: Apple's key endpoint failed or returned an unusable response""")
    public ResponseEntity<ApiResponse<AuthResponse>> appleLogin(@Valid @RequestBody AppleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.appleLogin(req.idToken(), req.fullName(), req.nonce())));
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
                    {"success": false, "data": null, "message": "Security alert: this session was refreshed twice, so it has been signed out. Please log in again", "errorCode": "auth.suspicious_login", "error": "Security alert: this session was refreshed twice, so it has been signed out. Please log in again"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Redis is unavailable, so the token couldn't be checked; it is not spent. Keep the tokens and retry later (`common.service_unavailable`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF), examples = @ExampleObject(value = """
                    {"success": false, "data": null, "message": "Service temporarily unavailable, please try again later", "errorCode": "common.service_unavailable", "error": "Service temporarily unavailable, please try again later"}""")))
    @Operation(summary = "Refresh access token",
            description = """
                    **Auth**: public (authenticated by the refresh token in the body, not by the Authorization header)
                    **Precondition**: a refresh token from a previous login or refresh
                    **Behavior**: rotates the refresh token: the old one is spent and a new access token and refresh token are returned. \
                    The response `user` is null; keep the user info from login. Roles are re-read from the database, so a role change takes effect on refresh; the token keeps the scope of its sign-in, so only an admin console session refreshes into a token that grants the admin role. \
                    A second call with the same old token within the grace window (default 60 s) returns the same new pair instead of failing, so concurrent refreshes are safe; \
                    not once that sign-in was logged out or revoked or its user suspended (401 `auth.refresh_token.invalid`)
                    **Side effects**: the old refresh token is spent. If a spent token is presented again after the grace window, that sign-in is ended: \
                    its latest refresh token can no longer be refreshed (401 `auth.refresh_token.invalid`), so it must sign in again once its access token expires; the user's other devices stay signed in. \
                    If the user is missing or suspended, all their refresh tokens are revoked too
                    **Errors**:
                    - 401 `auth.refresh_token.invalid`: the token is unknown, expired, revoked, or its user is missing or suspended
                    - 401 `auth.suspicious_login`: a spent token was reused after the grace window; the session it belonged to is now signed out
                    - 429 `common.rate_limited` / 503 `common.service_unavailable`: the token was not checked or spent; keep the tokens and retry later. Only the two 401s end the session""")
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
                    **Behavior**: revokes the given refresh token. A token that was already refreshed signs out the sign-in it belongs to: the refresh token that replaced it can no longer be refreshed. Other devices' refresh tokens stay valid
                    **Side effects**: the refresh token can no longer be refreshed. The access token is stateless and stays valid until it expires (default 15 minutes), so the client must discard it
                    **Errors**:
                    - 400 `common.validation_failed`: `refreshToken` is blank""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

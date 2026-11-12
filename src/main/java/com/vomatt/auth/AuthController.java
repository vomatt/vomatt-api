package com.vomatt.auth;

import com.vomatt.common.annotation.ApiError;
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
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.util.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
                    **Side effects**: sends an email; replaces any previous OTP for this address. A resend is refused during the cooldown (default 180 s), and each IP can request at most 20 OTPs for one address per UTC day, and all IPs together 60
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_IDENTIFIER_REQUIRED, when = "`email` is missing or blank")
    @ApiError(status = 400, key = MessageKey.AUTH_OTP_RESEND_COOLDOWN, when = "an OTP was sent too recently; the message states the seconds left", args = { "180" })
    @ApiError(status = 400, key = MessageKey.AUTH_EMAIL_SEND_FAILED, when = "the email provider rejected the message; the OTP is dropped and a resend is allowed after 30 s", args = { "SMTP timeout" })
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "this email is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected")
    @ApiError(status = 429, key = MessageKey.AUTH_OTP_DAILY_LIMIT, when = "20 OTPs were already requested for this address from this IP today (UTC), or 60 from all IPs")
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
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_IDENTIFIER_REQUIRED, when = "`email` is missing or blank")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_INVALID_OR_EXPIRED, when = "no OTP exists for this email (never sent, expired, already used, or its 10 guesses are used up)")
    @ApiError(status = 401, key = MessageKey.AUTH_INVALID_OTP, when = "the code is wrong")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "3rd wrong code, or this identifier is locked for the caller's IP (even a correct code is refused until the lock ends)")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the user is suspended (`active=false`)")
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
    @Operation(summary = "Send admin console email OTP",
            description = """
                    **Auth**: public
                    **Precondition**: none for the call; only an existing, active account holding the admin role receives the email
                    **Behavior**: like `POST /api/auth/send-otp` (6-digit OTP, TTL default 180 s, same email normalization), but the email is sent only to an active admin. \
                    Every other email gets the same 200, cooldown and daily count with no email sent, so the response never tells whether an email is an admin. \
                    Its OTPs, wrong-code counters, lockouts and resend cooldown are separate from the consumer login, so a code from `send-otp` does not work here and vice versa
                    **Side effects**: sends an email (to an active admin only, after the response; a provider failure is only logged, so retry after the cooldown if no email arrives); replaces any previous admin console OTP for this address. A resend is refused during the cooldown (default 180 s), and each IP can request at most 20 OTPs for one address per UTC day, and all IPs together 60
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_IDENTIFIER_REQUIRED, when = "`email` is missing or blank")
    @ApiError(status = 400, key = MessageKey.AUTH_OTP_RESEND_COOLDOWN, when = "an OTP was sent too recently; the message states the seconds left", args = { "180" })
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "this email is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected")
    @ApiError(status = 429, key = MessageKey.AUTH_OTP_DAILY_LIMIT, when = "20 OTPs were already requested for this address from this IP today (UTC), or 60 from all IPs")
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
    @Operation(summary = "Verify admin console email OTP and sign in",
            description = """
                    **Auth**: public
                    **Precondition**: an OTP was sent to this email via `POST /api/auth/admin/send-otp` and has not expired
                    **Behavior**: the only sign-in whose tokens grant the admin role (`/api/admin/**`). Verifies the OTP first (an email that is not an active admin never received one, so it fails like a wrong code), then checks the account is an active admin, and returns an access token and refresh token; \
                    refreshing them keeps the admin scope. Never creates an account. Wrong-code counting, lockouts and the 10-guess budget work as in `POST /api/auth/verify-otp`, separately from it
                    **Side effects**: the OTP is consumed on success; `lastLoginAt` is updated
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_IDENTIFIER_REQUIRED, when = "`email` is missing or blank")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_INVALID_OR_EXPIRED, when = "no admin console OTP exists for this email (never sent, expired, already used, or its 10 guesses are used up)")
    @ApiError(status = 401, key = MessageKey.AUTH_INVALID_OTP, when = "the code is wrong")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "3rd wrong code, or this email is locked for the caller's IP (even a correct code is refused until the lock ends)")
    @ApiError(status = 403, key = MessageKey.AUTH_ADMIN_FORBIDDEN, when = "the code was right but the account is no longer an admin (only possible for a code sent while it was one)")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the code was right but the admin was suspended since (`active=false`)")
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
                    and then `navigator.credentials.get({ publicKey })`, and send `credential.toJSON()` to `POST /api/auth/passkey/login/verify`. \
                    `allowCredentials` is always empty, so the user picks any passkey for vomatt.com. The admin console uses `POST /api/auth/admin/passkey/login/options` instead
                    **Side effects**: issues a one-time challenge that expires after 5 minutes
                    """)
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "too many auth requests from this IP")
    public ResponseEntity<ApiResponse<PasskeyLoginOptionsResponse>> passkeyLoginOptions() {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.loginOptions()));
    }

    @PostMapping("/passkey/login/verify")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = PASSKEY_LOGIN_BODY)))
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
                    """)
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`credential` or one of its required fields is missing")
    @ApiError(status = 401, key = MessageKey.AUTH_PASSKEY_INVALID, when = "the response is malformed or fails verification (unknown passkey, wrong signature, origin or RP ID, unknown, spent or expired challenge); get new options and retry")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the account is suspended")
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "too many auth requests from this IP")
    public ResponseEntity<ApiResponse<AuthResponse>> passkeyLoginVerify(@Valid @RequestBody PasskeyLoginVerifyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyPasskey(req)));
    }

    @PostMapping("/admin/passkey/login/options")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_PASSKEY_LOGIN_OPTIONS)))
    @Operation(summary = "Get admin console passkey sign-in options",
            description = """
                    **Auth**: public
                    **Precondition**: none; usernameless, like `POST /api/auth/passkey/login/options`
                    **Behavior**: the admin console counterpart of `POST /api/auth/passkey/login/options`, with the same response: pass `data` to \
                    `PublicKeyCredential.parseRequestOptionsFromJSON` and `navigator.credentials.get({ publicKey })` on https://admin.vomatt.com, \
                    and send `credential.toJSON()` to `POST /api/auth/admin/passkey/login/verify`. The challenge is accepted only there, \
                    and that endpoint accepts no other challenge
                    **Side effects**: issues a one-time challenge that expires after 5 minutes
                    """)
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "too many auth requests from this IP")
    public ResponseEntity<ApiResponse<PasskeyLoginOptionsResponse>> adminPasskeyLoginOptions() {
        return ResponseEntity.ok(ApiResponse.ok(passkeyService.adminLoginOptions()));
    }

    @PostMapping("/admin/passkey/login/verify")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_ADMIN_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = PASSKEY_LOGIN_BODY)))
    @Operation(summary = "Sign in to the admin console with a passkey",
            description = """
                    **Auth**: public
                    **Precondition**: a challenge from `POST /api/auth/admin/passkey/login/options` (a consumer sign-in challenge is refused), at most 5 minutes old and not yet used, \
                    and a passkey registered from the admin console (`POST /api/auth/passkey/register/verify` with an admin console access token)
                    **Behavior**: the passkey counterpart of `POST /api/auth/admin/verify-otp`: verifies the passkey as `POST /api/auth/passkey/login/verify` does, \
                    but accepts only the admin console origin (https://admin.vomatt.com), then requires a passkey registered from the admin console \
                    and an active admin owner, and returns tokens that grant the admin role (`/api/admin/**`); refreshing them keeps the admin scope
                    **Side effects**: the challenge is spent whether or not the sign-in succeeds, also when the owner is not an admin; on success the passkey's sign count and last use, and `lastLoginAt`, are updated
                    """)
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`credential` or one of its required fields is missing")
    @ApiError(status = 401, key = MessageKey.AUTH_PASSKEY_INVALID, when = "the response is malformed or fails verification (including a consumer sign-in challenge or a non-admin-console origin); get new options and retry")
    @ApiError(status = 403, key = MessageKey.AUTH_PASSKEY_ADMIN_SCOPE_REQUIRED, when = "the passkey was registered outside the admin console (including every passkey registered before this check existed); sign in with `POST /api/auth/admin/send-otp`, then register a passkey from the admin console")
    @ApiError(status = 403, key = MessageKey.AUTH_ADMIN_FORBIDDEN, when = "the passkey's owner is not an admin (same response as `POST /api/auth/admin/verify-otp`)")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the admin is suspended")
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "too many auth requests from this IP")
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
                    **Precondition**: none; registered and unregistered numbers get the same response, so it never tells whether a number has an account
                    **Behavior**: normalizes the number to E.164 (`0912345678` and `+886912345678` are the same number), generates a 6-digit OTP, stores it with a TTL (default 180 s) and sends it by SMS. \
                    `type` ("login" or "signup") is informational only; `POST /api/auth/phone/verify-otp` signs in an existing account or creates one and reports which in `isNewUser`
                    **Side effects**: sends an SMS; replaces any previous OTP for this number. A resend is refused during the cooldown (default 180 s), and each IP can request at most 5 OTPs for one number per UTC day, and all IPs together 15
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_PHONE_INVALID, when = "the number is neither a Taiwan mobile number (`09` + 8 digits) nor `+` country code and number (8-15 digits)")
    @ApiError(status = 400, key = MessageKey.AUTH_OTP_RESEND_COOLDOWN, when = "an OTP was sent too recently; the message states the seconds left", args = { "180" })
    @ApiError(status = 400, key = MessageKey.AUTH_SMS_SEND_FAILED, when = "the SMS provider rejected the message; the OTP is dropped and a resend is allowed after 30 s", args = { "provider error" })
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "this number is locked for the caller's IP after 3 wrong codes (5 minutes, doubling on each further lockout, up to 24 hours); other IPs are not affected")
    @ApiError(status = 429, key = MessageKey.AUTH_OTP_DAILY_LIMIT, when = "5 OTPs were already requested for this number from this IP today (UTC), or 15 from all IPs")
    @ApiError(status = 503, key = MessageKey.AUTH_SMS_UNAVAILABLE, when = "SMS is not available in this environment (production has no SMS provider yet); nothing is sent")
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
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_PHONE_INVALID, when = "the number is not in a supported format (see send-otp)")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_INVALID_OR_EXPIRED, when = "no OTP exists for this number (never sent, expired, already used, or its 10 guesses are used up)")
    @ApiError(status = 401, key = MessageKey.AUTH_INVALID_OTP, when = "the code is wrong")
    @ApiError(status = 401, key = MessageKey.AUTH_OTP_TOO_MANY_ATTEMPTS, when = "3rd wrong code, or this identifier is locked for the caller's IP (even a correct code is refused until the lock ends)")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the user is suspended (`active=false`)")
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
    @Operation(summary = "Sign in with Google",
            description = """
                    **Auth**: public
                    **Precondition**: the client obtained a Google ID token (Google Sign-In)
                    **Behavior**: verifies the ID token signature against Google's public keys, then its issuer, audience and expiry. Each ID token signs in once; presenting it again is refused. \
                    Signs in the user with that email, creating the user if new. An existing account with the same email is reused only when Google reports the email as verified
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated; the token is marked used until it expires
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_GOOGLE_NOT_CONFIGURED, when = "Google login is not configured on the server")
    @ApiError(status = 401, key = MessageKey.AUTH_GOOGLE_NO_EMAIL, when = "the token is invalid (malformed, bad signature, wrong issuer, expired) or carries no email")
    @ApiError(status = 401, key = MessageKey.AUTH_GOOGLE_INVALID_AUDIENCE, when = "the token was issued for a different client ID")
    @ApiError(status = 401, key = MessageKey.AUTH_OAUTH_TOKEN_REPLAYED, when = "this ID token was already used to sign in")
    @ApiError(status = 401, key = MessageKey.AUTH_EMAIL_NOT_VERIFIED, when = "Google reports the email as unverified")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the user is suspended (`active=false`)")
    @ApiError(status = 503, key = MessageKey.COMMON_SERVICE_UNAVAILABLE, when = "Google's key endpoint is unreachable")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(@Valid @RequestBody GoogleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.googleLogin(req.idToken())));
    }

    @PostMapping("/line")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"code": "abc123def456", "redirectUri": "https://app.example.com/auth/line/callback"}""")))
    @Operation(summary = "Sign in with LINE",
            description = """
                    **Auth**: public
                    **Precondition**: the client completed LINE Login and holds the authorization `code` and the `redirectUri` it used
                    **Behavior**: exchanges the code with LINE, reads the LINE profile and signs in the matching user, creating it if new. \
                    When LINE returns an id_token, it is verified with LINE and the login fails rather than proceed without that result. \
                    A synthetic `line_<userId>@line.oauth` email is used only when LINE returns no id_token, or verifies it but returns no email \
                    (the user granted no email scope), so `user.email` in the response is never null for LINE logins
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated
                    rate limited (429 / 408), did not answer in time or returned an empty or malformed verify body. The code is single-use and may already be spent: restart LINE Login for a new code""")
    @ApiError(status = 400, key = MessageKey.AUTH_LINE_NOT_CONFIGURED, when = "LINE login is not configured on the server")
    @ApiError(status = 401, key = MessageKey.AUTH_LINE_TOKEN_EXCHANGE_FAILED, when = "LINE rejected the code or the id_token (4xx), returned no access token or an unusable id_token, or the id_token belongs to another LINE user than the profile")
    @ApiError(status = 401, key = MessageKey.AUTH_LINE_PROFILE_FAILED, when = "LINE rejected the access token (4xx) or returned no profile")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the user is suspended (`active=false`)")
    @ApiError(status = 503, key = MessageKey.COMMON_SERVICE_UNAVAILABLE, when = "LINE's token, profile or id_token verify endpoint failed (5xx), rate limited (429 / 408), did not answer in time or returned an empty or malformed verify body. The code is single-use and may already be spent: restart LINE Login for a new code")
    public ResponseEntity<ApiResponse<AuthResponse>> lineLogin(@Valid @RequestBody LineAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.lineLogin(req.code(), req.redirectUri())));
    }

    @PostMapping("/apple")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_AUTH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"idToken": "eyJraWQiOiJZdXl1eVoxIiwiYWxnIjoiUlMyNTYifQ...", "fullName": "Ming Wang", "nonce": "f3a9c2b4e1d8a7c6b5e4d3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2"}""")))
    @Operation(summary = "Sign in with Apple",
            description = """
                    **Auth**: public
                    **Precondition**: the client generated a random `nonce`, passed its SHA-256 hex to Apple when starting Sign in with Apple, and obtained an identity token
                    **Behavior**: verifies the token signature against Apple's public keys, then its issuer, audience, expiry and that its `nonce` claim equals SHA-256 of the given `nonce`. \
                    Each identity token signs in once; presenting it again is refused. Signs in the user with the token's email, creating it if new. \
                    `fullName` is used only when a new user is created. An existing account with the same email is reused only when Apple reports the email as verified
                    **Side effects**: creates a user on first login, with a random bot avatar; `lastLoginAt` is updated; the token is marked used until it expires
                    """)
    @ApiError(status = 400, key = MessageKey.AUTH_APPLE_NOT_CONFIGURED, when = "Apple login is not configured on the server")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_INVALID_TOKEN, when = "the token is malformed or has no key ID")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_KEY_NOT_FOUND, when = "no Apple public key matches the token")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_INVALID_SIGNATURE, when = "the signature does not verify")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_INVALID_ISSUER, when = "the issuer is not Apple")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_INVALID_AUDIENCE, when = "the token was issued for a different client ID")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_TOKEN_EXPIRED, when = "the token has expired")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_INVALID_NONCE, when = "the token's `nonce` claim is missing or is not SHA-256 of the given `nonce`")
    @ApiError(status = 401, key = MessageKey.AUTH_OAUTH_TOKEN_REPLAYED, when = "this identity token was already used to sign in")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_NO_EMAIL, when = "the token carries no email")
    @ApiError(status = 401, key = MessageKey.AUTH_EMAIL_NOT_VERIFIED, when = "Apple reports the email as unverified")
    @ApiError(status = 401, key = MessageKey.AUTH_APPLE_LOGIN_FAILED, when = "any other failure while processing the token")
    @ApiError(status = 403, key = MessageKey.AUTH_ACCOUNT_BANNED, when = "the user is suspended (`active=false`)")
    @ApiError(status = 503, key = MessageKey.COMMON_SERVICE_UNAVAILABLE, when = "Apple's key endpoint failed or returned an unusable response")
    public ResponseEntity<ApiResponse<AuthResponse>> appleLogin(@Valid @RequestBody AppleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.appleLogin(req.idToken(), req.fullName(), req.nonce())));
    }

    @PostMapping("/refresh")
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = OK_REFRESH)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"refreshToken": "8c7e3f4a-1234-5678-90ab-cdef12345678"}""")))
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
                    """)
    @ApiError(status = 401, key = MessageKey.AUTH_REFRESH_TOKEN_INVALID, when = "the token is unknown, expired, revoked, or its user is missing or suspended")
    @ApiError(status = 401, key = MessageKey.AUTH_SUSPICIOUS_LOGIN, when = "a spent token was reused after the grace window; the session it belonged to is now signed out")
    @ApiError(status = 429, key = MessageKey.COMMON_RATE_LIMITED, when = "the token was not checked or spent; keep the tokens and retry later. Only the two 401s end the session")
    @ApiError(status = 503, key = MessageKey.COMMON_SERVICE_UNAVAILABLE, when = "the token was not checked or spent; keep the tokens and retry later. Only the two 401s end the session")
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
                    **Side effects**: the refresh token can no longer be refreshed, and the sign-in's access tokens get 401 `auth.session_revoked` from the next request on. A logout with an already refreshed token cuts the access tokens of the refresh token that replaced it
                    """)
    @ApiError(status = 400, key = MessageKey.COMMON_VALIDATION_FAILED, when = "`refreshToken` is blank")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

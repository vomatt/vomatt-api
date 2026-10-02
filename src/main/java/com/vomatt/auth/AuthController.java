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
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Auth - 認證", description = "使用者認證相關操作，支援 Email OTP、手機 OTP、Google、LINE、Apple 等登入方式，以及 token 刷新與登出")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-otp")
    @PublicApiResponse
    @Operation(summary = "發送 Email OTP 驗證碼")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> sendOtp(@Valid @RequestBody SendOtpRequest req) {
        authService.sendOtp(req.email(), null, null, Boolean.parseBoolean(req.checkRole()));
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/verify-otp")
    @PublicApiResponse
    @Operation(summary = "驗證 Email OTP 碼並取得 JWT")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        AuthResponse response = authService.verifyOtp(req.email(), null, req.code(), Boolean.parseBoolean(req.checkRole()));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/check-email")
    @PublicApiResponse
    @Operation(summary = "檢查 Email 是否已註冊")
    public ResponseEntity<ApiResponse<AuthEmailExistsResponse>> checkEmail(@Valid @RequestBody CheckEmailRequest req) {
        boolean exists = authService.checkEmailExists(req.email());
        return ResponseEntity.ok(ApiResponse.ok(new AuthEmailExistsResponse(exists)));
    }

    @PostMapping("/phone/send-otp")
    @PublicApiResponse
    @Operation(summary = "發送手機 OTP 驗證碼", description = "type: signup | login")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> phoneSendOtp(@Valid @RequestBody PhoneSendOtpRequest req) {
        authService.sendOtp(null, req.phone(), req.type(), false);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/phone/verify-otp")
    @PublicApiResponse
    @Operation(summary = "驗證手機 OTP 碼並取得 JWT")
    public ResponseEntity<ApiResponse<AuthResponse>> phoneVerifyOtp(@Valid @RequestBody PhoneVerifyOtpRequest req) {
        AuthResponse response = authService.verifyOtp(null, req.phone(), req.code(), false);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/google")
    @PublicApiResponse
    @Operation(summary = "Google 登入")
    public ResponseEntity<ApiResponse<AuthResponse>> googleLogin(@Valid @RequestBody GoogleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.googleLogin(req.idToken())));
    }

    @PostMapping("/line")
    @PublicApiResponse
    @Operation(summary = "LINE 登入")
    public ResponseEntity<ApiResponse<AuthResponse>> lineLogin(@Valid @RequestBody LineAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.lineLogin(req.code(), req.redirectUri())));
    }

    @PostMapping("/apple")
    @PublicApiResponse
    @Operation(summary = "Apple 登入")
    public ResponseEntity<ApiResponse<AuthResponse>> appleLogin(@Valid @RequestBody AppleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.appleLogin(req.idToken(), req.fullName())));
    }

    @PostMapping("/refresh")
    @PublicApiResponse
    @Operation(summary = "使用 Refresh Token 換發新 Access Token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req.refreshToken())));
    }

    @PostMapping("/logout")
    @PublicApiResponse
    @Operation(summary = "登出，使 Refresh Token 失效")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}

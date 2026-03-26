package com.vomattapi.application.controller;

import com.vomattapi.application.dto.auth.SigninRequest;
import com.vomattapi.application.dto.auth.PreSignupRequest;
import com.vomattapi.application.dto.auth.SignupRequest;
import com.vomattapi.application.dto.auth.TokenRefreshRequest;
import com.vomattapi.application.dto.common.ApiResponse;
import com.vomattapi.application.dto.common.BaseResponse;
import com.vomattapi.application.dto.auth.JwtResponse;
import com.vomattapi.application.dto.common.ErrorType;
import com.vomattapi.application.dto.auth.TokenRefreshResponse;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.infrastructure.security.services.UserDetailsImpl;
import com.vomattapi.application.service.auth.AuthService;
import com.vomattapi.application.service.auth.AuthSessionService;
import com.vomattapi.application.service.auth.JwtBlacklistService;
import com.vomattapi.application.service.auth.PreSignupService;
import com.vomattapi.application.service.auth.RefreshTokenService;
import com.vomattapi.application.service.auth.SignupService;
import com.vomattapi.domain.user.RefreshToken;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "認證", description = "認證相關的API，包括登入、註冊、刷新令牌和登出")
@Slf4j
@RequiredArgsConstructor
public class AuthController {
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final AuthService authService;
    private final AuthSessionService authSessionService;
    private final PreSignupService preSignupService;
    private final SignupService signupService;
    private final JwtBlacklistService jwtBlacklistService;

    @PostMapping("/signin")
    @RateLimiter(name = "signin")
    @Operation(summary = "會員登入", description = "使用電子郵件和驗證碼登入系統")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "登入成功",
                    content = @Content(schema = @Schema(implementation = JwtResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "認證失敗"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "登入嘗試次數過多，請稍後再試") })
    public ResponseEntity<ApiResponse<JwtResponse>> signin(
            @Parameter(description = "登入請求，包含電子郵件和驗證碼") @Valid @RequestBody SigninRequest signinRequest) {
        // 驗證碼無效時拋出 InvalidVerificationCodeException，由 GlobalExceptionHandler 處理
        authService.verifyCode(signinRequest.getEmail(), signinRequest.getVerificationCode());

        JwtResponse jwtResponse = authSessionService.createAuthenticatedSession(signinRequest.getEmail());
        log.info("User signed in successfully: {}", signinRequest.getEmail());
        return ResponseEntity.ok(ApiResponse.success(jwtResponse));
    }

    @PostMapping("/pre-signup")
    @RateLimiter(name = "pre-signup")
    @Operation(summary = "預註冊驗證", description = "檢查用戶名和email是否已存在，生成驗證碼並發送郵件")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "預註冊請求成功，驗證碼已發送"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "用戶名或email已存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "內部伺服器錯誤") })
    public ResponseEntity<ApiResponse<Void>> preSignup(
            @Parameter(description = "預註冊請求", required = true) @Valid @RequestBody PreSignupRequest request) {
        log.info("Pre-signup request received for email: {}, username: {}", request.getEmail(), request.getUsername());
        BaseResponse response = preSignupService.processPreSignup(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("Pre-signup successful. Please check your email."));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, response.getErrorCode()));
    }

    @PostMapping("/resend-verification")
    @RateLimiter(name = "resend-verification")
    @Operation(summary = "重發驗證碼", description = "重新發送預註冊驗證碼到指定郵箱")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "驗證碼重發成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "無效的郵箱或驗證已過期"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "請求過於頻繁") })
    public ResponseEntity<ApiResponse<Void>> resendVerificationCode(
            @Parameter(description = "郵箱地址", required = true) @RequestParam(name = "email") String email) {
        log.info("Resend verification code request for email: {}", email);
        BaseResponse response = preSignupService.resendVerificationCode(email);
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("Verification code resent successfully."));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, response.getErrorCode()));
    }

    @PostMapping("/signup")
    @Operation(summary = "會員註冊", description = "創建新會員帳戶")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "註冊成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "註冊資料無效，如用戶名已被使用") })
    public ResponseEntity<ApiResponse<JwtResponse>> registerUser(
            @Parameter(description = "註冊請求") @Valid @RequestBody SignupRequest signUpRequest) {
        var result = signupService.processSignup(signUpRequest);
        if (!result.isSuccess()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(result.getErrorType(), result.getErrorMessage()));
        }

        // 直接產生 JWT + RefreshToken，不透過 signin() 以避免繞過 Rate Limiter
        JwtResponse jwtResponse = authSessionService.createAuthenticatedSession(signUpRequest.getEmail());
        log.info("User registered and signed in: {}", signUpRequest.getEmail());
        return ResponseEntity.ok(ApiResponse.success(jwtResponse, "Registration successful"));
    }

    @PostMapping("/refreshToken")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌獲取新的訪問令牌")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "令牌刷新成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "刷新令牌無效或已過期") })
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refreshToken(
            @Parameter(description = "刷新令牌請求") @Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();
        TokenRefreshResponse tokenResponse = refreshTokenService.findByToken(requestRefreshToken)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String token = jwtUtils.generateTokenFromUsername(user.getUsername());
                    return new TokenRefreshResponse(token, requestRefreshToken);
                })
                .orElseThrow(() -> new TokenRefreshException(requestRefreshToken, "Refresh token is not in database!"));
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/signout")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "會員登出", description = "會員登出系統並清除刷新令牌")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "登出成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認證") })
    public ResponseEntity<ApiResponse<Void>> logoutUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        refreshTokenService.deleteByUserId(userDetails.getId());
        log.info("User signed out: {}", userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Log out successful!"));
    }

    @PostMapping("/force-expire-token")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "強制使JWT令牌過期", description = "立即使當前JWT令牌失效，並清除刷新令牌。用於安全登出或強制終止會話。")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "令牌已成功失效"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未認證或無效令牌") })
    public ResponseEntity<ApiResponse<Void>> forceExpireToken(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (headerAuth == null || !headerAuth.startsWith("Bearer ")) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(ErrorType.INVALID_CREDENTIALS, "No valid token found"));
        }

        String jwt = headerAuth.substring(7);
        long remainingMs = jwtUtils.getRemainingExpirationMs(jwt);
        jwtBlacklistService.blacklistToken(jwt, remainingMs);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            refreshTokenService.deleteByUserId(userDetails.getId());
        }

        log.info("JWT token forcefully expired");
        return ResponseEntity.ok(ApiResponse.success("Token successfully invalidated"));
    }

    @PostMapping("/generateVerificationCode")
    @RateLimiter(name = "resend-verification")
    @Operation(summary = "產生認證碼", description = "產生認證碼並發送至指定 email")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "認證碼生成成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "用戶不存在"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "認證碼生成失敗")
    })
    public ResponseEntity<ApiResponse<Void>> generateVerificationCode(
            @Parameter(description = "email") @RequestParam(name = "email") String email) {
        // EntityNotFoundException / BusinessRuleViolationException 由 GlobalExceptionHandler 處理
        authService.generateVerificationCode(email);
        log.info("Verification code generated for email: {}", email);
        return ResponseEntity.ok(ApiResponse.success("Verification code sent successfully."));
    }
}

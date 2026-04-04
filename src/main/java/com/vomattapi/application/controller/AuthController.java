package com.vomattapi.application.controller;

import com.vomattapi.application.dto.auth.EmailRequest;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Authentication-related APIs including sign in, sign up, refresh token and sign out")
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
    @Operation(summary = "Member Sign In", description = "Sign in to the system using email and verification code")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sign in successful",
                    content = @Content(schema = @Schema(implementation = JwtResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Too many sign in attempts, please try again later") })
    public ResponseEntity<ApiResponse<JwtResponse>> signin(
            @Parameter(description = "Sign in request containing email and verification code") @Valid @RequestBody SigninRequest signinRequest) {
        // When verification code is invalid, InvalidVerificationCodeException is thrown and handled by GlobalExceptionHandler
        authService.verifyCode(signinRequest.getEmail(), signinRequest.getVerificationCode());

        JwtResponse jwtResponse = authSessionService.createAuthenticatedSession(signinRequest.getEmail());
        log.info("User signed in successfully: {}", signinRequest.getEmail());
        return ResponseEntity.ok(ApiResponse.success(jwtResponse));
    }

    @PostMapping("/pre-signup")
    @RateLimiter(name = "pre-signup")
    @Operation(summary = "Pre-signup Verification", description = "Check if username and email already exist, generate verification code and send email")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Pre-signup request successful, verification code sent"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Username or email already exists"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Internal server error") })
    public ResponseEntity<ApiResponse<Void>> preSignup(
            @Parameter(description = "Pre-signup request", required = true) @Valid @RequestBody PreSignupRequest request) {
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
    @Operation(summary = "Resend Verification Code", description = "Resend pre-signup verification code to specified email")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verification code resent successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid email or verification expired"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Request too frequent") })
    public ResponseEntity<ApiResponse<Void>> resendVerificationCode(
            @Parameter(description = "Email address", required = true) @Valid @RequestBody EmailRequest request) {
        String email = request.getEmail();
        log.info("Resend verification code request for email: {}", email);
        BaseResponse response = preSignupService.resendVerificationCode(email);
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("Verification code resent successfully."));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorType.BUSINESS_RULE_VIOLATION, response.getErrorCode()));
    }

    @PostMapping("/signup")
    @Operation(summary = "Member Sign Up", description = "Create a new member account")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sign up successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid sign up data, such as username already used") })
    public ResponseEntity<ApiResponse<JwtResponse>> registerUser(
            @Parameter(description = "Sign up request") @Valid @RequestBody SignupRequest signUpRequest) {
        var result = signupService.processSignup(signUpRequest);
        if (!result.isSuccess()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(result.getErrorType(), result.getErrorMessage()));
        }

        // Generate JWT + RefreshToken directly, not through signin() to avoid bypassing Rate Limiter
        JwtResponse jwtResponse = authSessionService.createAuthenticatedSession(signUpRequest.getEmail());
        log.info("User registered and signed in: {}", signUpRequest.getEmail());
        return ResponseEntity.ok(ApiResponse.success(jwtResponse, "Registration successful"));
    }

    @PostMapping("/refreshToken")
    @Operation(summary = "Refresh Token", description = "Use refresh token to get a new access token")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refresh successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Refresh token invalid or expired") })
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refreshToken(
            @Parameter(description = "Refresh token request") @Valid @RequestBody TokenRefreshRequest request) {
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
    @Operation(summary = "Member Sign Out", description = "Sign out from the system and clear refresh token")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sign out successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated") })
    public ResponseEntity<ApiResponse<Void>> logoutUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        refreshTokenService.deleteByUserId(userDetails.getId());
        log.info("User signed out: {}", userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Log out successful!"));
    }

    @PostMapping("/force-expire-token")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Force Expire JWT Token", description = "Immediately invalidate the current JWT token and clear refresh token. Used for secure logout or forced session termination.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token successfully invalidated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated or invalid token") })
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
    @Operation(summary = "Generate Verification Code", description = "Generate verification code and send to specified email")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verification code generated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User does not exist"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Verification code generation failed")
    })
    public ResponseEntity<ApiResponse<Void>> generateVerificationCode(
            @Parameter(description = "email") @Valid @RequestBody EmailRequest request) {
        String email = request.getEmail();
        // EntityNotFoundException / BusinessRuleViolationException handled by GlobalExceptionHandler
        authService.generateVerificationCode(email);
        log.info("Verification code generated for email: {}", email);
        return ResponseEntity.ok(ApiResponse.success("Verification code sent successfully."));
    }
}

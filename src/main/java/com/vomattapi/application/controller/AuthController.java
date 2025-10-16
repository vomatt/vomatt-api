package com.vomattapi.application.controller;

import com.vomattapi.application.dto.request.LoginRequest;
import com.vomattapi.application.dto.request.PreSignupRequest;
import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.request.TokenRefreshRequest;
import com.vomattapi.application.dto.response.BaseResponse;
import com.vomattapi.application.dto.response.JwtResponse;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.dto.response.ErrorCode;
import com.vomattapi.application.dto.response.PreSignupResponse;
import com.vomattapi.application.dto.response.TokenRefreshResponse;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.application.security.jwt.JwtUtils;
import com.vomattapi.application.security.services.UserDetailsImpl;
import com.vomattapi.application.service.AuthService;
import com.vomattapi.application.service.UserService;
import com.vomattapi.application.service.PreSignupService;
import com.vomattapi.application.security.services.UserDetailsServiceImpl;
import com.vomattapi.application.service.RefreshTokenService;
import com.vomattapi.application.service.SignupService;
import com.vomattapi.domain.user.RefreshToken;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
@Tag(name = "認證", description = "認證相關的API，包括登入、註冊、刷新令牌和登出")
@Slf4j
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final AuthService authService;
    private final UserService userService;
    private final UserDetailsServiceImpl userDetailsService;
    private final PreSignupService preSignupService;
    private final SignupService signupService;
    private final com.vomattapi.application.service.JwtBlacklistService jwtBlacklistService;

    @PostMapping("/signin")
    @RateLimiter(name = "login")
    @Operation(summary = "會員登入", description = "使用電子郵件和驗證碼登入系統")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登入成功", content = @Content(schema = @Schema(implementation = JwtResponse.class))),
            @ApiResponse(responseCode = "401", description = "認證失敗"),
            @ApiResponse(responseCode = "429", description = "登入嘗試次數過多，請稍後再試") })
    public ResponseEntity<?> authenticateUser(
            @Parameter(description = "登入請求，包含電子郵件和驗證碼") @Valid @RequestBody LoginRequest loginRequest) {

        try {
            // Verify the verification code first
            boolean isCodeValid = authService.verificationCode(loginRequest.getEmail(), loginRequest.getVerificationCode());

            if (!isCodeValid) {
                log.warn("Invalid verification code for email: {}", loginRequest.getEmail());
                return ResponseEntity.status(401).body(new BaseResponse(false, ErrorCode.INVALID_VERIFICATION_CODE.getCode()));
            }

            // Find user by email
            UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByEmail(loginRequest.getEmail());

            if (userDetails == null) {
                log.warn("User not found for email: {}", loginRequest.getEmail());
                return ResponseEntity.status(401).body(new BaseResponse(false, ErrorCode.USER_NOT_FOUND.getCode()));
            }

            // Create authentication token manually since verification code is valid
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Generate JWT token
            String jwt = jwtUtils.generateJwtToken(authentication);

            // Get user roles
            List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());

            // Create refresh token
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(userDetails.getId());

            log.info("User signed in successfully: {}", loginRequest.getEmail());

            return ResponseEntity.ok(
                new JwtResponse(jwt, refreshToken.getToken(), userDetails.getId(),
                    userDetails.getUsername(), userDetails.getEmail(), roles));

        } catch (Exception e) {
            log.error("Authentication failed for email: {}", loginRequest.getEmail(), e);
            return ResponseEntity.status(401).body(new BaseResponse(false, ErrorCode.AUTHENTICATION_FAILED.getCode()));
        }
    }

    @PostMapping("/pre-signup")
    @RateLimiter(name = "pre-signup")
    @Operation(summary = "預註冊驗證", description = "檢查用戶名和email是否已存在，生成驗證碼並發送郵件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "預註冊請求成功，驗證碼已發送", content = @Content(schema = @Schema(implementation = PreSignupResponse.class))),
            @ApiResponse(responseCode = "400", description = "用戶名或email已存在"),
            @ApiResponse(responseCode = "500", description = "內部伺服器錯誤") })
    public ResponseEntity<BaseResponse> preSignup(
            @Parameter(description = "預註冊請求", required = true) @Valid @RequestBody PreSignupRequest request) {
        try {
            log.info("Pre-signup request received for email: {}, username: {}", request.getEmail(),
                    request.getUsername());

            BaseResponse response = preSignupService.processPreSignup(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Pre-signup request failed for email: {}", request.getEmail(), e);
            return ResponseEntity.internalServerError()
                    .body(new BaseResponse(false, ErrorCode.INTERNAL_ERROR.getCode()));
        }
    }

    @PostMapping("/resend-verification")
    @RateLimiter(name = "resend-verification")
    @Operation(summary = "重發驗證碼", description = "重新發送預註冊驗證碼到指定郵箱")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "驗證碼重發成功", content = @Content(schema = @Schema(implementation = PreSignupResponse.class))),
            @ApiResponse(responseCode = "400", description = "無效的郵箱或驗證已過期"),
            @ApiResponse(responseCode = "429", description = "請求過於頻繁") })
    public ResponseEntity<BaseResponse> resendVerificationCode(
            @Parameter(description = "郵箱地址", required = true) @RequestParam(name = "email", required = true) String email) {
        try {
            log.info("Resend verification code request for email: {}", email);
            
            BaseResponse response = preSignupService.resendVerificationCode(email);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            log.error("Resend verification code failed for email: {}", email, e);
            return ResponseEntity.internalServerError()
                    .body(new BaseResponse(false, ErrorCode.INTERNAL_ERROR.getCode()));
        }
    }

    @PostMapping("/signup")
    @Operation(summary = "會員註冊", description = "創建新會員帳戶")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "註冊成功", content = @Content(schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "400", description = "註冊資料無效，如用戶名已被使用") })
    public ResponseEntity<BaseResponse> registerUser(
            @Parameter(description = "註冊請求，包含用戶名、電子郵件、密碼等") @Valid @RequestBody SignupRequest signUpRequest) {
        // Delegate to SignupService
        var result = signupService.processSignup(signUpRequest);
        BaseResponse response = new BaseResponse(result.isSuccess(), result.getErrorMessage());

        if (result.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/refreshToken")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌獲取新的訪問令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "令牌刷新成功", content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class))),
            @ApiResponse(responseCode = "403", description = "刷新令牌無效或已過期") })
    public ResponseEntity<?> refreshToken(
            @Parameter(description = "刷新令牌請求") @Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenService.findByToken(requestRefreshToken).map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUser).map(user -> {
                    String token = jwtUtils.generateTokenFromUsername(user.getUsername());
                    return ResponseEntity.ok(new TokenRefreshResponse(token, requestRefreshToken));
                })
                .orElseThrow(() -> new TokenRefreshException(requestRefreshToken, "Refresh token is not in database!"));
    }

    @PostMapping("/signout")
    @Operation(summary = "會員登出", description = "會員登出系統並清除刷新令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登出成功", content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "401", description = "未認證") })
    public ResponseEntity<?> logoutUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetailsImpl memberDetails = (UserDetailsImpl) authentication.getPrincipal();
        String userId = memberDetails.getId();

        refreshTokenService.deleteByUserId(userId);
        return ResponseEntity.ok(new MessageResponse("Log out successful!"));
    }

    @PostMapping("/force-expire-token")
    @Operation(summary = "強制使JWT令牌過期", description = "立即使當前JWT令牌失效，並清除刷新令牌。用於安全登出或強制終止會話。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "令牌已成功失效", content = @Content(schema = @Schema(implementation = com.vomattapi.application.dto.response.ApiResponse.class))),
            @ApiResponse(responseCode = "401", description = "未認證或無效令牌") })
    public ResponseEntity<com.vomattapi.application.dto.response.ApiResponse<Void>> forceExpireToken(
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            // Extract JWT from request header
            String headerAuth = request.getHeader("Authorization");
            if (headerAuth != null && headerAuth.startsWith("Bearer ")) {
                String jwt = headerAuth.substring(7);

                // Get remaining expiration time
                long remainingMs = jwtUtils.getRemainingExpirationMs(jwt);

                // Add token to blacklist
                jwtBlacklistService.blacklistToken(jwt, remainingMs);

                // Also delete refresh token for the user
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl) {
                    UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
                    refreshTokenService.deleteByUserId(userDetails.getId());
                }

                log.info("JWT token forcefully expired");
                return ResponseEntity.ok(
                    com.vomattapi.application.dto.response.ApiResponse.<Void>success("Token successfully invalidated")
                );
            } else {
                return ResponseEntity.status(401).body(
                    com.vomattapi.application.dto.response.ApiResponse.<Void>error(ErrorCode.INVALID_CREDENTIALS, "No valid token found")
                );
            }
        } catch (Exception e) {
            log.error("Failed to force expire token", e);
            return ResponseEntity.status(500).body(
                com.vomattapi.application.dto.response.ApiResponse.<Void>error(ErrorCode.INTERNAL_ERROR)
            );
        }
    }

    @GetMapping("/generateVerificationCode")
    @Operation(summary = "產生認證碼", description = "產生認證碼")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "認證碼生成成功"),
        @ApiResponse(responseCode = "404", description = "用戶不存在"),
        @ApiResponse(responseCode = "400", description = "認證碼生成失敗")
    })
    public ResponseEntity<BaseResponse> generateVerificationCode(
            @Parameter(description = "email") @RequestParam(name = "email", required = true) String email) {
        try {
            String verificationCode = authService.generateVerificationCode(email);
            log.info("generateVerificationCode for email: {}, verificationCode: {}", email, verificationCode);
            if (verificationCode != null) {
                return ResponseEntity.ok(new BaseResponse(true));
            } else {
                return ResponseEntity.badRequest().body(new BaseResponse(false, ErrorCode.GENERATE_VERIFICATION_CODE_FAILED.getCode()));
            }
        } catch (EntityNotFoundException e) {
            log.warn("User not found for email: {}", email);
            return ResponseEntity.internalServerError().body(new BaseResponse(false, ErrorCode.USER_NOT_FOUND.getCode()));
        }
    }
}
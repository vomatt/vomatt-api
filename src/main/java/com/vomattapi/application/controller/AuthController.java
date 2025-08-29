package com.vomattapi.application.controller;

import com.vomattapi.application.dto.request.LoginRequest;
import com.vomattapi.application.dto.request.PreSignupRequest;
import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.request.TokenRefreshRequest;
import com.vomattapi.application.dto.response.JwtResponse;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.dto.response.PreSignupResponse;
import com.vomattapi.application.dto.response.TokenRefreshResponse;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.application.security.jwt.JwtUtils;
import com.vomattapi.application.security.services.MemberDetailsImpl;
import com.vomattapi.application.service.AuthService;
import com.vomattapi.application.service.MemberService;
import com.vomattapi.application.service.PreSignupService;
import com.vomattapi.application.service.RefreshTokenService;
import com.vomattapi.application.service.SignupService;
import com.vomattapi.domain.member.RefreshToken;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MemberService memberService;
    private final PreSignupService preSignupService;
    private final SignupService signupService;

    @PostMapping("/signin")
    @RateLimiter(name = "login")
    @Operation(summary = "會員登入", description = "使用用戶名和密碼登入系統")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登入成功", content = @Content(schema = @Schema(implementation = JwtResponse.class))),
            @ApiResponse(responseCode = "401", description = "認證失敗"),
            @ApiResponse(responseCode = "429", description = "登入嘗試次數過多，請稍後再試") })
    public ResponseEntity<?> authenticateUser(
            @Parameter(description = "登入請求，包含用戶名和密碼") @Valid @RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getVerifyCode()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();

        String jwt = jwtUtils.generateJwtToken(authentication);

        List<String> roles = memberDetails.getAuthorities().stream().map(item -> item.getAuthority())
                .collect(Collectors.toList());

        RefreshToken refreshToken = refreshTokenService.createRefreshToken(memberDetails.getId());

        return ResponseEntity.ok(
                new JwtResponse(jwt, refreshToken.getToken(), memberDetails.getId(), memberDetails.getUsername(),
                        memberDetails.getEmail(), roles));
    }

    @PostMapping("/pre-signup")
    @RateLimiter(name = "pre-signup")
    @Operation(summary = "預註冊驗證", description = "檢查用戶名和email是否已存在，生成驗證碼並發送郵件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "預註冊請求成功，驗證碼已發送", content = @Content(schema = @Schema(implementation = PreSignupResponse.class))),
            @ApiResponse(responseCode = "400", description = "用戶名或email已存在"),
            @ApiResponse(responseCode = "500", description = "內部伺服器錯誤") })
    public ResponseEntity<PreSignupResponse> preSignup(
            @Parameter(description = "預註冊請求", required = true) @Valid @RequestBody PreSignupRequest request) {
        try {
            log.info("Pre-signup request received for email: {}, username: {}", request.getEmail(),
                    request.getUsername());

            PreSignupResponse response = preSignupService.processPreSignup(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Pre-signup request failed for email: {}", request.getEmail(), e);
            return ResponseEntity.internalServerError()
                    .body(new PreSignupResponse(false, "Internal server error", null, 0));
        }
    }

    @PostMapping("/signup")
    @Operation(summary = "會員註冊", description = "創建新會員帳戶")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "註冊成功", content = @Content(schema = @Schema(implementation = MessageResponse.class))),
            @ApiResponse(responseCode = "400", description = "註冊資料無效，如用戶名已被使用") })
    public ResponseEntity<?> registerUser(
            @Parameter(description = "註冊請求，包含用戶名、電子郵件、密碼等") @Valid @RequestBody SignupRequest signUpRequest) {
        // Delegate to SignupService
        var result = signupService.processSignup(signUpRequest);

        if (result.isSuccess()) {
            return ResponseEntity.ok(result.toMessageResponse());
        } else {
            return ResponseEntity.badRequest().body(result.toMessageResponse());
        }
    }

    @PostMapping("/refreshtoken")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌獲取新的訪問令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "令牌刷新成功", content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class))),
            @ApiResponse(responseCode = "403", description = "刷新令牌無效或已過期") })
    public ResponseEntity<?> refreshtoken(
            @Parameter(description = "刷新令牌請求") @Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenService.findByToken(requestRefreshToken).map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getMember).map(user -> {
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
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        String userId = memberDetails.getId();

        refreshTokenService.deleteByUserId(userId);
        return ResponseEntity.ok(new MessageResponse("Log out successful!"));
    }

    @GetMapping("/generateVerifyCode")
    @Operation(summary = "產生認證碼", description = "產生認證碼")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "登入成功") })
    public ResponseEntity<?> generateVerifyCode(
            @Parameter(description = "email") @RequestParam(name = "email", required = true) String email) {
        log.info("generateVerifyCode for email: {}", email);
        String verifyCode = authService.generateVerifyCode(email);
        if (verifyCode != null) {
            return ResponseEntity.ok(verifyCode);
        } else {
            return ResponseEntity.badRequest().body(new MessageResponse("Failed to generate verification code"));
        }
    }
}
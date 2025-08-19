package com.vomattapi.application.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomattapi.application.dto.request.LoginRequest;
import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.request.TokenRefreshRequest;
import com.vomattapi.application.dto.response.JwtResponse;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.dto.response.TokenRefreshResponse;
import com.vomattapi.application.exception.TokenRefreshException;
import com.vomattapi.application.security.jwt.JwtUtils;
import com.vomattapi.application.security.services.MemberDetailsImpl;
import com.vomattapi.application.service.RefreshTokenService;
import com.vomattapi.domain.member.ERole;
import com.vomattapi.domain.member.Member;
import com.vomattapi.domain.member.RefreshToken;
import com.vomattapi.domain.member.Role;
import com.vomattapi.domain.member.repository.MemberRepository;
import com.vomattapi.domain.member.repository.RoleRepository;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
@Tag(name = "認證", description = "認證相關的API，包括登入、註冊、刷新令牌和登出")
public class AuthController {
    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JwtUtils jwtUtils;

    @Autowired
    RefreshTokenService refreshTokenService;

    @PostMapping("/signin")
    @RateLimiter(name = "login")
    @Operation(summary = "會員登入", description = "使用用戶名和密碼登入系統")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "登入成功", 
                     content = @Content(schema = @Schema(implementation = JwtResponse.class))),
        @ApiResponse(responseCode = "401", description = "認證失敗"),
        @ApiResponse(responseCode = "429", description = "登入嘗試次數過多，請稍後再試")
    })
    public ResponseEntity<?> authenticateUser(
            @Parameter(description = "登入請求，包含用戶名和密碼")
            @Valid @RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        
        String jwt = jwtUtils.generateJwtToken(authentication);
        
        List<String> roles = memberDetails.getAuthorities().stream().map(item -> item.getAuthority())
                .collect(Collectors.toList());

        RefreshToken refreshToken = refreshTokenService.createRefreshToken(memberDetails.getId());

        return ResponseEntity.ok(new JwtResponse(jwt, refreshToken.getToken(), memberDetails.getId(),
                memberDetails.getUsername(), memberDetails.getEmail(), roles));
    }

    @PostMapping("/signup")
    @Operation(summary = "會員註冊", description = "創建新會員帳戶")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "註冊成功", 
                     content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @ApiResponse(responseCode = "400", description = "註冊資料無效，如用戶名已被使用")
    })
    public ResponseEntity<?> registerUser(
            @Parameter(description = "註冊請求，包含用戶名、電子郵件、密碼等")
            @Valid @RequestBody SignupRequest signUpRequest) {
        if (memberRepository.existsByUsername(signUpRequest.getUsername())) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Username is already taken!"));
        }

        if (memberRepository.existsByEmail(signUpRequest.getEmail())) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already in use!"));
        }

        if (signUpRequest.getPhoneNumber() != null && !signUpRequest.getPhoneNumber().isEmpty() 
                && memberRepository.existsByPhoneNumber(signUpRequest.getPhoneNumber())) {
            return ResponseEntity.badRequest().body(new MessageResponse("Error: Phone number is already in use!"));
        }

        // Create new member's account
        Member member = new Member(
                signUpRequest.getUsername(), 
                signUpRequest.getEmail(),
                signUpRequest.getPhoneNumber(),
                encoder.encode(signUpRequest.getPassword()));

        Set<String> strRoles = signUpRequest.getRoles();
        Set<Role> roles = new HashSet<>();

        if (strRoles == null) {
            Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
            roles.add(userRole);
        } else {
            strRoles.forEach(role -> {
                switch (role) {
                case "admin":
                    Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                            .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                    roles.add(adminRole);
                    break;
                case "mod":
                    Role modRole = roleRepository.findByName(ERole.ROLE_MODERATOR)
                            .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                    roles.add(modRole);
                    break;
                default:
                    Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                            .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                    roles.add(userRole);
                }
            });
        }

        member.setRoles(roles);
        memberRepository.save(member);

        return ResponseEntity.ok(new MessageResponse("User registered successfully!"));
    }

    @PostMapping("/refreshtoken")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌獲取新的訪問令牌")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "令牌刷新成功", 
                     content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class))),
        @ApiResponse(responseCode = "403", description = "刷新令牌無效或已過期")
    })
    public ResponseEntity<?> refreshtoken(
            @Parameter(description = "刷新令牌請求")
            @Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenService.findByToken(requestRefreshToken)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getMember)
                .map(user -> {
                    String token = jwtUtils.generateTokenFromUsername(user.getUsername());
                    return ResponseEntity.ok(new TokenRefreshResponse(token, requestRefreshToken));
                })
                .orElseThrow(() -> new TokenRefreshException(requestRefreshToken,
                        "Refresh token is not in database!"));
    }

    @PostMapping("/signout")
    @Operation(summary = "會員登出", description = "會員登出系統並清除刷新令牌")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "登出成功", 
                     content = @Content(schema = @Schema(implementation = MessageResponse.class))),
        @ApiResponse(responseCode = "401", description = "未認證")
    })
    public ResponseEntity<?> logoutUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        MemberDetailsImpl memberDetails = (MemberDetailsImpl) authentication.getPrincipal();
        String userId = memberDetails.getId();
        
        refreshTokenService.deleteByUserId(userId);
        return ResponseEntity.ok(new MessageResponse("Log out successful!"));
    }
}
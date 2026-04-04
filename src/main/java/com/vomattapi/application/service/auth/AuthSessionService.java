package com.vomattapi.application.service.auth;

import com.vomattapi.application.dto.auth.JwtResponse;
import com.vomattapi.domain.user.RefreshToken;
import com.vomattapi.infrastructure.security.jwt.JwtUtils;
import com.vomattapi.infrastructure.security.services.UserDetailsImpl;
import com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Responsible for creating authenticated sessions (JWT + RefreshToken)
 * Unified session creation logic for signin and signup
 */
@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final UserDetailsServiceImpl userDetailsService;

    /**
     * Create authenticated session based on email, return JwtResponse
     */
    public JwtResponse createAuthenticatedSession(String email) {
        UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByEmail(email);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String jwt = jwtUtils.generateJwtToken(authentication);
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(userDetails.getId());

        return new JwtResponse(jwt, refreshToken.getToken(), userDetails.getId(),
                userDetails.getUsername(), userDetails.getEmail(), roles);
    }
}

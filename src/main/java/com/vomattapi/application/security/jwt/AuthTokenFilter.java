package com.vomattapi.application.security.jwt;

import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vomattapi.application.security.services.MemberDetailsServiceImpl;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class AuthTokenFilter extends OncePerRequestFilter {
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private MemberDetailsServiceImpl memberDetailsService;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class);

    // 定義不需要身份驗證的路徑，保持与WebSecurityConfig一致
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
        "/api/auth/signin",
        "/api/auth/signup",
        "/api/auth/refreshtoken",
        "/api/public/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/api-docs/**",
        "/v3/api-docs/**",
        "/h2-console/**",
        "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        // 打印完整请求信息以进行调试
        logger.debug("====== REQUEST INFO START ======");
        logger.debug("Request path: {}", path);
        logger.debug("Request method: {}", method);
        logger.debug("Request query string: {}", request.getQueryString());
        
        // 打印所有请求头
        logger.debug("Request headers:");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            // 隐藏敏感头部信息(如Authorization)的具体内容
            if ("authorization".equalsIgnoreCase(headerName)) {
                headerValue = headerValue != null ? headerValue.substring(0, Math.min(10, headerValue.length())) + "..." : null;
            }
            logger.debug("  {} : {}", headerName, headerValue);
        }
        logger.debug("====== REQUEST INFO END ======");
        
        // 如果是signup或signin等公开路径，直接放行
        for (String publicPath : PUBLIC_PATHS) {
            if (pathMatcher.match(publicPath, path)) {
                logger.debug("Path {} matches public path pattern {}, authentication not required", path, publicPath);
                return true;
            }
        }
        
        logger.debug("Path {} requires authentication", path);
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (!shouldNotFilter(request)) {
                String jwt = parseJwt(request);
                logger.debug("JWT token: {}", jwt != null ? "present" : "not present");
                
                if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                    String username = jwtUtils.getUserNameFromJwtToken(jwt);
                    logger.debug("Username from JWT: {}", username);

                    UserDetails userDetails = memberDetailsService.loadUserByUsername(username);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    logger.debug("User authenticated successfully");
                }
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}
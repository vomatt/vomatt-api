package com.vomattapi.application.security;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.vomattapi.application.security.jwt.AuthEntryPointJwt;
import com.vomattapi.application.security.jwt.AuthTokenFilter;
import com.vomattapi.application.security.services.MemberDetailsServiceImpl;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSecurityConfig.class);
    
    // 定義公開的路徑
    private static final String[] PUBLIC_URLS = {
        "/api/auth/signin",
        "/api/auth/signup",
        "/api/auth/pre-signup",
        "/api/auth/refreshtoken",
        "/api/auth/generateVerifyCode",
        "/api/public/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/api-docs/**",
        "/v3/api-docs/**",
        "/h2-console/**",
        "/actuator/**"
    };
    
    private final MemberDetailsServiceImpl userDetailsService;
    private final AuthEntryPointJwt unauthorizedHandler;
    
    public WebSecurityConfig(MemberDetailsServiceImpl userDetailsService, AuthEntryPointJwt unauthorizedHandler) {
        this.userDetailsService = userDetailsService;
        this.unauthorizedHandler = unauthorizedHandler;
    }
    
    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter();
    }
    
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }
    
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        logger.debug("Configuring SecurityFilterChain");
        
        http
            // 先配置CORS和CSRF
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            
            // 配置异常处理
            .exceptionHandling(exception -> {
                exception.authenticationEntryPoint(unauthorizedHandler);
                logger.debug("Setting unauthorized handler: {}", unauthorizedHandler.getClass().getSimpleName());
            })
            
            // 配置会话管理
            .sessionManagement(session -> {
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
                logger.debug("Session management policy set to STATELESS");
            })
            
            // 配置请求授权规则
            .authorizeHttpRequests(auth -> {
                // 为所有公共URL配置路径匹配器
                for (String url : PUBLIC_URLS) {
                    logger.debug("Adding permitAll for URL pattern: {}", url);
                }
                
                // 确保认证端点不需要认证即可访问
                auth.requestMatchers(PUBLIC_URLS).permitAll()
                    // 其他所有请求需要认证
                    .anyRequest().authenticated();
                logger.debug("Authorization rules configured");
            })
            
            // 配置头信息
            .headers(headers -> {
                headers.frameOptions(frameOptions -> frameOptions.sameOrigin());
                logger.debug("Headers configured");
            });
        
        // 注册认证提供者和JWT过滤器
        http.authenticationProvider(authenticationProvider());
        
        // 确保JWT过滤器在UsernamePasswordAuthenticationFilter之前执行
        http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);
        logger.debug("JWT filter added to filter chain");
        
        return http.build();
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        logger.debug("Configuring CORS");
        
        CorsConfiguration configuration = new CorsConfiguration();
        // 允许所有来源
        configuration.setAllowedOrigins(List.of("*"));
        // 允许所有HTTP方法
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 允许所有常见头部
        configuration.setAllowedHeaders(Arrays.asList(
            "Origin", "X-Requested-With", "Content-Type", "Accept", "Authorization",
            "Access-Control-Allow-Origin", "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods", "Access-Control-Allow-Credentials"
        ));
        // 允许浏览器暴露的响应头
        configuration.setExposedHeaders(Arrays.asList("Authorization", "x-auth-token"));
        // 对于预检请求，这个设置决定预检结果的有效期
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        logger.debug("CORS configuration completed");
        return source;
    }
}
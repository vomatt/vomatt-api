package com.vomatt.common.config;

import com.vomatt.common.filter.RateLimitFilter;
import com.vomatt.common.security.JwtAuthFilter;
import com.vomatt.common.security.JwtAuthenticationEntryPoint;
import com.vomatt.common.security.JwtAccessDeniedHandler;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static jakarta.servlet.DispatcherType.ERROR;
import static jakarta.servlet.DispatcherType.FORWARD;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final org.springframework.core.env.Environment environment;

    // In production, set to the actual frontend domains (comma-separated)
    @Value("${app.security.cors-allowed-origins}")
    private List<String> corsAllowedOrigins;

    // Peers allowed to set X-Forwarded-For (comma-separated CIDRs); blank keeps ClientIpResolver's default list,
    // except in production: the default trusts every private range, so a directly reachable port would let any client
    // spoof its IP and sidestep rate limits and OTP lockouts
    @Value("${app.security.trusted-proxies:}")
    void setTrustedProxies(String cidrs) {
        if ((cidrs == null || cidrs.isBlank()) && environment.matchesProfiles("prod")) {
            throw new IllegalStateException("TRUSTED_PROXIES must be set in production: list the ingress CIDRs allowed to set X-Forwarded-For.");
        }
        ClientIpResolver.setTrustedProxies(cidrs);
    }

    // Shared with vomatt-web, which sends it with the user's IP (ClientIpResolver.CLIENT_IP_HEADER); blank ignores that header
    @Value("${app.security.client-ip-secret:}")
    void setClientIpSecret(String secret) {
        if (secret != null && !secret.isBlank() && secret.length() < 32) {
            throw new IllegalStateException("CLIENT_IP_SECRET must be at least 32 characters.");
        }
        ClientIpResolver.setClientIpSecret(secret);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Spring Security 7: FORWARD/ERROR dispatches must be permitted explicitly
                        .dispatcherTypeMatchers(FORWARD, ERROR).permitAll()
                        // Public: Swagger UI & OpenAPI docs
                        .requestMatchers(SecurityEndpoints.PUBLIC_SWAGGER).permitAll()
                        // Public: auth endpoints (OTP, OAuth)
                        .requestMatchers(SecurityEndpoints.PUBLIC_AUTH).permitAll()
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_USERS).authenticated()
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_VOTES).authenticated()
                        // Public GET: tags, vote list / detail / ended results, public user profile
                        .requestMatchers(HttpMethod.GET, SecurityEndpoints.PUBLIC_GET).permitAll()
                        // Public: Actuator health check
                        .requestMatchers(SecurityEndpoints.PUBLIC_ACTUATOR).permitAll()
                        // Admin endpoints (ADMIN role required)
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_ADMIN).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Content-Type", "Authorization", "Accept"));
        // Not CORS-safelisted; the frontend reads it on 429
        config.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        // Auth is a Bearer header, never a cookie: credentials mode would only matter if a cookie session appeared,
        // and it forbids wildcard origins, which tempts people to over-widen the list
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

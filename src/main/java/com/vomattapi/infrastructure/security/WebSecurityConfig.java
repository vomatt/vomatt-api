package com.vomattapi.infrastructure.security;

import com.vomattapi.infrastructure.config.SecurityConfigurationProperties;
import com.vomattapi.infrastructure.security.jwt.AuthEntryPointJwt;
import com.vomattapi.infrastructure.security.jwt.AuthTokenFilter;
import com.vomattapi.infrastructure.security.services.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class WebSecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final AuthEntryPointJwt unauthorizedHandler;
    private final AuthTokenFilter authTokenFilter;
    private final SecurityConfigurationProperties securityProperties;

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        return new DaoAuthenticationProvider(userDetailsService);
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
        http.csrf(AbstractHttpConfigurer::disable).cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public: auth endpoints that require no token
                        .requestMatchers(SecurityEndpoints.PUBLIC_AUTH).permitAll()
                        // Authenticated: auth endpoints that require a valid token
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_AUTH).authenticated()
                        // Public GET: tags, votes (fine-grained checks via @PreAuthorize)
                        .requestMatchers(HttpMethod.GET, SecurityEndpoints.PUBLIC_GET).permitAll()
                        // Public GET: user public profile by username only (/me and /search require auth)
                        .requestMatchers(HttpMethod.GET, SecurityEndpoints.PUBLIC_GET_USER_PROFILE).permitAll()
                        // Public: Swagger UI & OpenAPI docs
                        .requestMatchers(SecurityEndpoints.PUBLIC_SWAGGER).permitAll()
                        // Public: Actuator health check
                        .requestMatchers(SecurityEndpoints.PUBLIC_ACTUATOR).permitAll()
                        // Authenticated: admin endpoints (role enforced via @PreAuthorize)
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_ADMIN).authenticated()
                        // All other requests require authentication
                        .anyRequest().authenticated());

        http.authenticationProvider(authenticationProvider());
        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(securityProperties.getCorsAllowedOrigins());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

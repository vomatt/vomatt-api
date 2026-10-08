package com.vomatt.common.config;

import com.vomatt.common.filter.RateLimitFilter;
import com.vomatt.common.security.JwtAuthFilter;
import com.vomatt.common.security.SecurityEndpoints;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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

    // 正式環境須設為實際前端網域（逗號分隔）；搭配 allowCredentials 不可用萬用字元
    @Value("${app.security.cors-allowed-origins}")
    private List<String> corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Spring Security 7: FORWARD/ERROR dispatch 需明確放行
                        .dispatcherTypeMatchers(FORWARD, ERROR).permitAll()
                        // Public: Swagger UI & OpenAPI docs
                        .requestMatchers(SecurityEndpoints.PUBLIC_SWAGGER).permitAll()
                        // Public: auth endpoints (OTP, OAuth)
                        .requestMatchers(SecurityEndpoints.PUBLIC_AUTH).permitAll()
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_USERS).authenticated()
                        .requestMatchers(SecurityEndpoints.AUTHENTICATED_VOTES).authenticated()
                        // Public GET: tags, vote list, public user profile
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
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

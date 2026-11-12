package com.flashsale.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security: no server session, no cookies, no CSRF. Requests carry a JWT access token
 * in the Authorization header, verified by the {@code JwtDecoder} bean (signature, expiry, issuer,
 * revocation). Any instance can serve any request.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/error",
        "/api/v1/auth/register",
        "/api/v1/auth/verify-otp",
        "/api/v1/auth/resend-otp",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/demo/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ProblemAuthenticationHandlers problems) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth ->
                        auth.requestMatchers(PUBLIC_PATHS)
                                .permitAll()
                                .requestMatchers(HttpMethod.OPTIONS, "/**")
                                .permitAll()
                                .requestMatchers("/api/v1/admin/**")
                                .hasRole("ADMIN")
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(rs ->
                        rs.jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                                .authenticationEntryPoint(problems.entryPoint()))
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(problems.entryPoint())
                                .accessDeniedHandler(problems.accessDeniedHandler()))
                .build();
    }

    /** Maps the {@code role} claim (USER / ADMIN) to Spring authorities ROLE_USER / ROLE_ADMIN. */
    private static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

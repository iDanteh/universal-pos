package com.posplatform.config;

import com.posplatform.modules.identity.infrastructure.AppSessionRepository;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SECURITY POSTURE from v0.2 (ADR-011, real authentication):
 *
 *  - Default DENY: every endpoint not explicitly public requires an ACTIVE
 *    DB session (opaque HttpOnly cookie). Login itself is the only public
 *    auth surface; everything else fails closed (401).
 *  - SessionAuthFilter establishes the principal AND resolves the tenant
 *    (X-Tenant-Id) strictly server-side: identity mirror + live Membership
 *    read inside RLS. Client input can never choose a tenant it doesn't
 *    belong to — RLS is the last line, this filter the first.
 *  - Public surface stays minimal: health, OpenAPI docs, read-only system
 *    status and the login endpoint.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SessionAuthFilter sessionAuthFilter(AppSessionRepository appSessionRepository,
                                               AppUserOrganizationRepository userOrganizationRepository,
                                               MembershipRepository membershipRepository,
                                               TenantTransactionRunner tenantRunner) {
        return new SessionAuthFilter(
                appSessionRepository, userOrganizationRepository, membershipRepository, tenantRunner);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, SessionAuthFilter filter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api/v1/system/status",
                    "/api/v1/auth/login",
                    "/error"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                            org.springframework.http.HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}

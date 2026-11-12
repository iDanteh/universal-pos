package com.posplatform.config;

import com.posplatform.modules.identity.AppSession;
import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.infrastructure.AppSessionRepository;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.shared.tenancy.TenantContext;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SESSION + TENANT RESOLUTION filter (ADR-011) — the ONLY place where a
 * request's identity is established:
 *
 *  1. AUTHENTICATION: opaque HttpOnly cookie -> SHA-256 -> DB session
 *     (ACTIVE, not expired) -> SecurityContext principal.
 *  2. TENANT RESOLUTION: optional X-Tenant-Id header. The candidate tenant
 *     is verified SERVER-SIDE in TWO layers:
 *       a) the identity-resolution mirror written only by trusted onboarding;
 *       b) a LIVE Membership read INSIDE the tenant's RLS scope (the DB,
 *          not the application, decides whether the user belongs there).
 *     The TenantContext ThreadLocal is then set so @TenantScoped methods
 *     can bind the GUC in their transactions. Missing/invalid claims keep
 *     the request authenticated but tenant-less (endpoints demanding a
 *     tenant fail closed).
 *
 * The filter never issues queries through RLS-protected tables outside a
 * tenant-bound transaction (the window that would leak cross-tenant rows).
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "pos_session";
    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String REQUEST_USER_ID = "posplatform.userId";

    private final AppSessionRepository appSessionRepository;
    private final AppUserOrganizationRepository userOrganizationRepository;
    private final MembershipRepository membershipRepository;
    private final TenantTransactionRunner tenantRunner;

    public SessionAuthFilter(AppSessionRepository appSessionRepository,
                             AppUserOrganizationRepository userOrganizationRepository,
                             MembershipRepository membershipRepository,
                             TenantTransactionRunner tenantRunner) {
        this.appSessionRepository = appSessionRepository;
        this.userOrganizationRepository = userOrganizationRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRunner = tenantRunner;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Optional<AppSession> session = authenticate(rawToken(request));
            if (session.isPresent()) {
                UUID userId = session.get().getUserId();
                request.setAttribute(REQUEST_USER_ID, userId);

                SecurityContextHolder.getContext().setAuthentication(authenticationOf(userId));

                resolveTenant(request, userId);
            }
            chain.doFilter(request, response);
        } finally {
            // Never let a thread-bound tenant leak into the next request (pool reuse).
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<AppSession> authenticate(String rawToken) {
        if (rawToken == null) {
            return Optional.empty();
        }
        return appSessionRepository.findByTokenHash(com.posplatform.modules.identity.application.AuthService.sha256Hex(rawToken))
                .filter(session -> session.isActive(Instant.now()));
    }

    private static Authentication authenticationOf(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private void resolveTenant(HttpServletRequest request, UUID userId) {
        String candidate = request.getHeader(HEADER_TENANT);
        if (candidate == null || candidate.isBlank()) {
            return;
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(candidate.trim());
        } catch (IllegalArgumentException ex) {
            return;
        }

        // Layer 1: mirror written only by trusted onboarding.
        boolean mirrored = userOrganizationRepository
                .findByUserIdAndOrganizationId(userId, tenantId)
                .isPresent();
        if (!mirrored) {
            return;
        }

        // Layer 2: live membership INSIDE the tenant's RLS scope.
        boolean activeMembership = Boolean.TRUE.equals(tenantRunner.run(tenantId, () ->
                membershipRepository.findByOrganizationIdAndUserId(tenantId, userId)
                        .map(membership -> membership.getStatus() == Membership.Status.ACTIVE)
                        .orElse(false)));
        if (!activeMembership) {
            return;
        }

        TenantContext.setTenantId(tenantId);
    }

    private static String rawToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

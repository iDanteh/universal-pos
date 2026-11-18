package com.posplatform.modules.identity.application;

import com.posplatform.modules.identity.AppSession;
import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.AppUserOrganization;
import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.infrastructure.AppSessionRepository;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * AUTH use cases (ADR-011): login, logout and session identity.
 *
 * SECURITY INVARIANTS:
 *  - The password is verified with BCrypt; wrong email vs wrong password
 *    produce THE SAME generic error (no user enumeration).
 *  - The raw session token (256-bit random) exists only in the HttpOnly
 *    cookie. We persist its SHA-256, so a DB leak cannot mint sessions.
 *  - Sessions are revocable server-side and expire (30 days).
 *  - Tenant context is NEVER set here: the browser chooses among the
 *    organizations verified by onboarding, and every tenant claim is
 *    later re-verified against real Membership inside RLS (filter).
 */
@Service
@Validated
public class AuthService {

    public static final Duration SESSION_TTL = Duration.ofDays(30);

    public record LoginCommand(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthenticatedUser(UUID id, String email, String displayName) {
    }

    public record OrganizationRef(UUID organizationId, String organizationName, boolean primary) {
    }

    public record LoginOutcome(AppSession session, String rawToken,
                               AuthenticatedUser user, List<OrganizationRef> organizations) {
    }

    public record MeOutcome(AuthenticatedUser user, List<OrganizationRef> organizations) {
    }

    private final AppUserRepository appUserRepository;
    private final AppSessionRepository appSessionRepository;
    private final AppUserOrganizationRepository userOrganizationRepository;
    private final MembershipRepository membershipRepository;
    private final TenantTransactionRunner tenantRunner;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(AppUserRepository appUserRepository,
                       AppSessionRepository appSessionRepository,
                       AppUserOrganizationRepository userOrganizationRepository,
                       MembershipRepository membershipRepository,
                       TenantTransactionRunner tenantRunner) {
        this.appUserRepository = appUserRepository;
        this.appSessionRepository = appSessionRepository;
        this.userOrganizationRepository = userOrganizationRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRunner = tenantRunner;
    }

    /** Domain error carrying NO detail: identical message for wrong email/password. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Credenciales inválidas");
        }
    }

    @Transactional
    public LoginOutcome login(@Valid LoginCommand command) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(command.email().trim())
                .orElseThrow(InvalidCredentialsException::new);

        String hash = user.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(command.password(), hash)) {
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() != AppUser.Status.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        String rawToken = newToken();
        AppSession session = appSessionRepository.save(new AppSession(
                user.getId(),
                sha256Hex(rawToken),
                Instant.now().plus(SESSION_TTL)));

        return new LoginOutcome(
                session,
                rawToken,
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName()),
                organizationsOf(user.getId()));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        appSessionRepository.findByTokenHash(sha256Hex(rawToken)).ifPresent(AppSession::revoke);
    }

    @Transactional(readOnly = true)
    public MeOutcome me(String rawToken) {
        AppSession session = requireActiveSession(rawToken);
        AppUser user = appUserRepository.findById(session.getUserId())
                .orElseThrow(InvalidCredentialsException::new);
        return new MeOutcome(
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName()),
                organizationsOf(user.getId()));
    }

    @Transactional(readOnly = true)
    public AppSession requireActiveSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidCredentialsException();
        }
        AppSession session = appSessionRepository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(InvalidCredentialsException::new);
        if (!session.isActive(Instant.now())) {
            throw new InvalidCredentialsException();
        }
        return session;
    }

    /**
     * The mirror supplies CANDIDATES only (ADR-011); since v0.10 (ADR-020)
     * each candidate is re-verified against a real ACTIVE Membership INSIDE
     * that tenant's RLS scope, so a revoked org stops appearing immediately.
     */
    private List<OrganizationRef> organizationsOf(UUID userId) {
        return userOrganizationRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .filter(ref -> tenantRunner.run(ref.getOrganizationId(), () ->
                        membershipRepository.findByOrganizationIdAndUserId(
                                        ref.getOrganizationId(), userId)
                                .map(membership -> membership.getStatus() == Membership.Status.ACTIVE)
                                .orElse(false)))
                .map(ref -> new OrganizationRef(
                        ref.getOrganizationId(), ref.getOrganizationName(), ref.isPrimary()))
                .toList();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

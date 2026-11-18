package com.posplatform.modules.identity.application;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.MembershipRole;
import com.posplatform.modules.identity.Role;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRoleRepository;
import com.posplatform.modules.identity.infrastructure.RoleRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MEMBER lifecycle use cases (v0.10 · ADR-020): invite, accept, list, change
 * roles and revoke.
 *
 * INVARIANTS:
 *  - The invitation token is the credential: "<tenantId>:<secret 256-bit>".
 *    Only its SHA-256 is persisted. The tenant id rides in clear SOLO to open
 *    the right RLS scope on accept (the accept endpoint has no session); the
 *    secret cannot be derived from it.
 *  - The mirror (app_user_organization) is written by the trusted
 *    provisioning component in the SAME transaction as the Membership insert
 *    (ADR-011): it never grants access, only candidacy.
 *  - The LAST-ADMIN guard: no mutation can leave the organization without an
 *    ACTIVE membership holding a system role.
 *  - Memberships are never deleted: PENDING → ACTIVE → REVOKED.
 */
@Service
public class UsersService {

    public static final Duration INVITATION_TTL = Duration.ofDays(7);

    public record MemberRoleRef(UUID roleId, String roleName) {
    }

    public record MemberView(UUID membershipId, UUID userId, String email,
                             String displayName, String status,
                             List<MemberRoleRef> roles,
                             boolean invitationPending,
                             Instant invitedAt, Instant acceptedAt) {
    }

    public record InviteCommand(String email, String displayName, List<UUID> roleIds) {
    }

    public record InviteOutcome(MemberView member, String invitationToken) {
    }

    public record AcceptInvitationCommand(String token, String password) {
    }

    public record AcceptInvitationOutcome(UUID organizationId, String organizationName,
                                          String email, String displayName) {
    }

    private final MembershipRepository membershipRepository;
    private final MembershipRoleRepository membershipRoleRepository;
    private final RoleRepository roleRepository;
    private final AppUserRepository appUserRepository;
    private final OrganizationRepository organizationRepository;
    private final IdentityUserProvisioning userProvisioning;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public UsersService(MembershipRepository membershipRepository,
                        MembershipRoleRepository membershipRoleRepository,
                        RoleRepository roleRepository,
                        AppUserRepository appUserRepository,
                        OrganizationRepository organizationRepository,
                        IdentityUserProvisioning userProvisioning,
                        OutboxWriter outboxWriter,
                        TenantTransactionRunner tenantRunner,
                        JdbcTemplate jdbc) {
        this.membershipRepository = membershipRepository;
        this.membershipRoleRepository = membershipRoleRepository;
        this.roleRepository = roleRepository;
        this.appUserRepository = appUserRepository;
        this.organizationRepository = organizationRepository;
        this.userProvisioning = userProvisioning;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Queries ----

    public List<MemberView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<Membership> memberships = membershipRepository.findByOrganizationId(tenantId);
            Map<UUID, List<MembershipRole>> linksByMembership = new LinkedHashMap<>();
            for (MembershipRole link : membershipRoleRepository.findByTenantId(tenantId)) {
                linksByMembership
                        .computeIfAbsent(link.getMembership().getId(), ignored -> new ArrayList<>())
                        .add(link);
            }
            return memberships.stream()
                    .map(membership -> viewOf(membership,
                            linksByMembership.getOrDefault(membership.getId(), List.of())))
                    .toList();
        });
    }

    // ---- Invite ----

    @Transactional
    public InviteOutcome invite(UUID tenantId, UUID inviterUserId, InviteCommand command) {
        String email = command.email() == null ? "" : command.email().trim();
        if (email.isEmpty() || !email.contains("@")) {
            throw new IllegalArgumentException("El correo del invitado es requerido");
        }
        if (command.roleIds() == null || command.roleIds().isEmpty()) {
            throw new IllegalArgumentException("Asigna al menos un rol al invitado");
        }
        return tenantRunner.run(tenantId, () -> {
            Organization organization = organizationRepository.findById(tenantId)
                    .orElseThrow(() -> new NotFoundException("organization", tenantId.toString()));

            List<Role> roles = resolveRoles(command.roleIds());

            // Trusted provisioning: AppUser (reused if the person already
            // exists in another org) + mirror row, same transaction (ADR-011).
            String displayName = (command.displayName() == null || command.displayName().isBlank())
                    ? email.substring(0, email.indexOf('@'))
                    : command.displayName().trim();
            AppUser user = userProvisioning.createUserOrExisting(email, displayName,
                    tenantId, organization.getName());

            if (membershipRepository.findByOrganizationIdAndUserId(tenantId, user.getId()).isPresent()) {
                throw new ConflictException("MEMBER_EXISTS",
                        "Esa persona ya es member de la organización");
            }

            Membership membership = new Membership(organization, user, Membership.Status.PENDING);
            String rawToken = newInvitationToken(tenantId);
            membership.attachInvitation(AuthService.sha256Hex(rawToken),
                    Instant.now().plus(INVITATION_TTL), inviterUserId);
            membershipRepository.save(membership);
            for (Role role : roles) {
                membershipRoleRepository.save(new MembershipRole(tenantId, membership, role));
            }

            outboxWriter.append(tenantId, "identity.membership.invited", "membership",
                    membership.getId(), Map.of(
                            "email", user.getEmail(),
                            "roleIds", roles.stream().map(Role::getId).toList()));

            return new InviteOutcome(viewOf(membership,
                    membershipRoleRepository.findByMembershipId(membership.getId())), rawToken);
        });
    }

    /** Resend: a NEW token invalidates the previous link (only PENDING). */
    @Transactional
    public InviteOutcome resend(UUID tenantId, UUID membershipId, UUID inviterUserId) {
        return tenantRunner.run(tenantId, () -> {
            Membership membership = requireMembership(membershipId);
            if (membership.getStatus() != Membership.Status.PENDING) {
                throw new ConflictException("MEMBERSHIP_NOT_PENDING",
                        "Solo una invitación pendiente puede reenviarse");
            }
            String rawToken = newInvitationToken(tenantId);
            membership.reissueInvitation(AuthService.sha256Hex(rawToken),
                    Instant.now().plus(INVITATION_TTL), inviterUserId);
            membershipRepository.save(membership);

            outboxWriter.append(tenantId, "identity.membership.invited", "membership",
                    membershipId, Map.of("email", membership.getUser().getEmail(), "resent", true));

            return new InviteOutcome(viewOf(membership,
                    membershipRoleRepository.findByMembershipId(membershipId)), rawToken);
        });
    }

    // ---- Accept (public; the token carries the tenant to open RLS scope) ----

    @Transactional
    public AcceptInvitationOutcome accept(AcceptInvitationCommand command) {
        String token = command.token() == null ? "" : command.token().trim();
        int separator = token.indexOf(':');
        if (separator <= 0) {
            throw new ConflictException("INVITATION_INVALID", "La invitación no es válida");
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(token.substring(0, separator));
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("INVITATION_INVALID", "La invitación no es válida");
        }
        return tenantRunner.run(tenantId, () -> {
            Membership membership = membershipRepository
                    .findByInvitationTokenHash(AuthService.sha256Hex(token))
                    .orElseThrow(() -> new ConflictException("INVITATION_INVALID",
                            "La invitación no es válida"));

            if (membership.getStatus() == Membership.Status.ACTIVE) {
                // Replay: the invitation was already consumed — same outcome, 200.
                return outcomeOf(membership);
            }
            if (membership.getStatus() != Membership.Status.PENDING) {
                throw new ConflictException("INVITATION_INVALID", "La invitación no es válida");
            }
            if (membership.getInvitationExpiresAt() != null
                    && membership.getInvitationExpiresAt().isBefore(Instant.now())) {
                throw new ConflictException("INVITATION_EXPIRED",
                        "La invitación expiró; pide un reenvío");
            }

            AppUser user = membership.getUser();
            if (user.getPasswordHash() == null) {
                String password = command.password() == null ? "" : command.password();
                if (password.length() < 8) {
                    throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres");
                }
                user.setPasswordHash(passwordEncoder.encode(password));
                appUserRepository.save(user);
            }
            membership.acceptInvitation(Instant.now());
            membershipRepository.save(membership);

            outboxWriter.append(tenantId, "identity.membership.activated", "membership",
                    membership.getId(), Map.of("email", user.getEmail()));

            return outcomeOf(membership);
        });
    }

    // ---- Mutations on existing members ----

    @Transactional
    public MemberView changeRoles(UUID tenantId, UUID membershipId, List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new IllegalArgumentException("Asigna al menos un rol");
        }
        return tenantRunner.run(tenantId, () -> {
            Membership membership = requireMembership(membershipId);
            List<Role> roles = resolveRoles(roleIds);
            if (isAdmin(membership) && roles.stream().noneMatch(Role::isSystem)
                    && !hasAnotherAdmin(membershipId)) {
                throw new ConflictException("LAST_ADMIN",
                        "La organización necesita al menos un administrador activo");
            }

            List<MembershipRole> existing = membershipRoleRepository.findByMembershipId(membershipId);
            membershipRoleRepository.deleteAll(existing);
            // flush the deletes BEFORE re-inserting: re-granting the same
            // (membership, role) pair in one flush would violate the UNIQUE
            // constraint before Hibernate orders the statements.
            membershipRoleRepository.flush();
            for (Role role : roles) {
                membershipRoleRepository.save(new MembershipRole(tenantId, membership, role));
            }

            outboxWriter.append(tenantId, "identity.membership.role_changed", "membership",
                    membershipId, Map.of("email", membership.getUser().getEmail(),
                            "roleIds", roles.stream().map(Role::getId).toList()));

            return viewOf(membership, membershipRoleRepository.findByMembershipId(membershipId));
        });
    }

    @Transactional
    public MemberView revoke(UUID tenantId, UUID membershipId) {
        return tenantRunner.run(tenantId, () -> {
            Membership membership = requireMembership(membershipId);
            if (membership.getStatus() == Membership.Status.REVOKED) {
                throw new ConflictException("MEMBERSHIP_ALREADY_REVOKED",
                        "El member ya está revocado");
            }
            if (isAdmin(membership) && !hasAnotherAdmin(membershipId)) {
                throw new ConflictException("LAST_ADMIN",
                        "La organización necesita al menos un administrador activo");
            }
            membership.revoke();
            membershipRepository.save(membership);

            outboxWriter.append(tenantId, "identity.membership.revoked", "membership",
                    membershipId, Map.of("email", membership.getUser().getEmail()));

            return viewOf(membership, membershipRoleRepository.findByMembershipId(membershipId));
        });
    }

    // ---- Helpers ----

    private MemberView viewOf(Membership membership, List<MembershipRole> links) {
        List<MemberRoleRef> roles = links.stream()
                .map(link -> new MemberRoleRef(link.getRole().getId(), link.getRole().getName()))
                .toList();
        boolean pending = membership.getStatus() == Membership.Status.PENDING;
        return new MemberView(
                membership.getId(),
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                membership.getStatus().name(),
                roles,
                pending,
                membership.getCreatedAt(),
                membership.getInvitationAcceptedAt());
    }

    private AcceptInvitationOutcome outcomeOf(Membership membership) {
        return new AcceptInvitationOutcome(
                membership.getTenantId(),
                membership.getOrganization().getName(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName());
    }

    private Membership requireMembership(UUID membershipId) {
        return membershipRepository.findById(membershipId)
                .orElseThrow(() -> new NotFoundException("membership", membershipId.toString()));
    }

    private List<Role> resolveRoles(List<UUID> roleIds) {
        // Inside the tenant's RLS scope only same-tenant roles are visible:
        // a foreign/unknown id is simply missing from the result → 404.
        List<Role> roles = roleRepository.findAllById(new LinkedHashSet<>(roleIds)).stream().toList();
        if (roles.size() != new LinkedHashSet<>(roleIds).size()) {
            throw new NotFoundException("role", "de la lista enviada");
        }
        return roles;
    }

    private boolean isAdmin(Membership membership) {
        return membershipRoleRepository.findByMembershipId(membership.getId()).stream()
                .anyMatch(link -> link.getRole().isSystem());
    }

    /** TRUE when at least one OTHER ACTIVE membership holds a system role. */
    private boolean hasAnotherAdmin(UUID membershipId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT m.id)
                        FROM membership m
                        JOIN membership_role mr ON mr.membership_id = m.id
                                               AND mr.tenant_id = m.tenant_id
                        JOIN role r ON r.id = mr.role_id
                                   AND r.tenant_id = mr.tenant_id
                        WHERE m.tenant_id = current_setting('app.tenant_id', true)::uuid
                          AND m.id <> ?
                          AND m.status = 'ACTIVE'
                          AND r.is_system = true
                        """,
                Long.class, membershipId);
        return count != null && count > 0;
    }

    private String newInvitationToken(UUID tenantId) {
        byte[] secret = new byte[32];
        secureRandom.nextBytes(secret);
        return tenantId + ":" + HexFormat.of().formatHex(secret);
    }
}

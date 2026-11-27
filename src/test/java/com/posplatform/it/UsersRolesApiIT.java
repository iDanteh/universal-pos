package com.posplatform.it;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardOrganizationResult;
import com.posplatform.modules.organization.application.OnboardingService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * USERS & ROLES REST (fase 10 · ADR-020): the invitation lifecycle
 * (invite -> token -> public accept -> ACTIVE), role authoring with the
 * immutable system rule, the LAST-ADMIN guard, permission ENFORCEMENT on
 * business endpoints (a limited member gets 403 PERMISSION_DENIED) and
 * revocation killing tenant access (fail-closed, RLS-driven).
 */
@AutoConfigureMockMvc
class UsersRolesApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(UUID organizationId, String email, String password) {
    }

    private Seeded seedUser(String tag) {
        String email = "users-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org users " + tag,
                email,
                "Owner " + tag,
                "Users " + tag + " SA de CV",
                null, null,
                "Sucursal Centro", "CDMX"));
        return new Seeded(org.organizationId(), email, password);
    }

    private Cookie sessionOf(Seeded seeded) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(
                                seeded.email(), seeded.password())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("pos_session");
        assertThat(session).isNotNull();
        return session;
    }

    private String ownerMembershipId(Seeded seeded) throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/v1/users")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        String body = listed.getResponse().getContentAsString();
        String email = seeded.email();
        // the member whose email is the owner's
        int members = com.jayway.jsonpath.JsonPath.read(body, "length()");
        for (int i = 0; i < members; i++) {
            String memberEmail = com.jayway.jsonpath.JsonPath.read(body, "$[" + i + "].email");
            if (memberEmail.equals(email)) {
                return com.jayway.jsonpath.JsonPath.read(body, "$[" + i + "].membershipId");
            }
        }
        throw new AssertionError("owner membership not found");
    }

    /** Deterministic member lookup: jsonPath inline filters are fragile. */
    private Map<?, ?> findMemberByEmail(String body, String email) {
        List<Map<?, ?>> members = com.jayway.jsonpath.JsonPath.read(body, "$");
        return members.stream()
                .filter(member -> email.equals(member.get("email")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("member not found: " + email));
    }

    private String createCustomRole(Seeded seeded, String name, String permissions) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/roles")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","permissions":%s}
                                """.formatted(name, permissions)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id");
    }

    // ---- tests ----

    @Test
    void inviteAcceptEnforcesAndRevokes() throws Exception {
        var owner = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = owner.organizationId().toString();
        Cookie ownerSession = sessionOf(owner);

        // custom LIMITED role: can read sales/catalog, nothing else
        String roleId = createCustomRole(owner, "Vendedor", "[\"sales.read\",\"catalog.read\"]");

        // invite a new person
        String memberEmail = "vendedor-%s@example.com".formatted(UUID.randomUUID().toString().substring(0, 6));
        MvcResult invited = mockMvc.perform(post("/api/v1/users/invitations")
                        .cookie(ownerSession)
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","displayName":"Vendedor Uno","roleIds":["%s"]}
                                """.formatted(memberEmail, roleId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.member.status").value("PENDING"))
                .andExpect(jsonPath("$.member.invitationPending").value(true))
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                invited.getResponse().getContentAsString(), "$.invitationToken");

        // the member list already shows the PENDING member with the role
        MvcResult membersListed = mockMvc.perform(get("/api/v1/users")
                        .cookie(ownerSession)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> memberRow = findMemberByEmail(
                membersListed.getResponse().getContentAsString(), memberEmail);
        assertThat(memberRow.get("status")).isEqualTo("PENDING");
        String roleName = com.jayway.jsonpath.JsonPath.read(memberRow, "$.roles[0].roleName");
        assertThat(roleName).isEqualTo("Vendedor");

        // accept: PUBLIC, no session; sets the password and activates
        mockMvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"M3mbro-Seguro!\"}"
                                .formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(memberEmail))
                .andExpect(jsonPath("$.organizationId").value(tenant));

        // token replay (already consumed) still answers 200 idempotently
        mockMvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk());

        // the new member logs in and reads the org
        MvcResult memberLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"M3mbro-Seguro!\"}"
                                .formatted(memberEmail)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie memberSession = memberLogin.getResponse().getCookie("pos_session");

        // ENFORCEMENT: allowed reads pass...
        mockMvc.perform(get("/api/v1/sales").cookie(memberSession).header("X-Tenant-Id", tenant))
                .andExpect(status().isOk());
        // ...users management is 403 PERMISSION_DENIED (no users.manage)...
        mockMvc.perform(get("/api/v1/users").cookie(memberSession).header("X-Tenant-Id", tenant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        // ...issuing a sale is 403 (no sales.execute)...
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(memberSession)
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "enforce-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"lines\":[],\"payments\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        // ...and /auth/permissions mirrors the effective grant set (UI gating)
        mockMvc.perform(get("/api/v1/auth/permissions")
                        .cookie(memberSession)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // the member sees ONLY the orgs with an ACTIVE membership
        MvcResult me = mockMvc.perform(get("/api/v1/auth/me").cookie(memberSession))
                .andExpect(status().isOk())
                .andReturn();
        int orgCount = com.jayway.jsonpath.JsonPath.read(
                me.getResponse().getContentAsString(), "$.organizations.length()");
        assertThat(orgCount).isEqualTo(1);

        // revocation: the owner revokes the member; access dies server-side
        MvcResult listed = mockMvc.perform(get("/api/v1/users")
                        .cookie(ownerSession)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andReturn();
        String membershipId = String.valueOf(
                findMemberByEmail(listed.getResponse().getContentAsString(), memberEmail)
                        .get("membershipId"));
        mockMvc.perform(post("/api/v1/users/{id}/revoke", membershipId)
                        .cookie(ownerSession)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // the revoked member's request FAILS CLOSED: Layer 2 of SessionAuthFilter
        // rejects the REVOKED membership so the tenant is NOT bound anymore —
        // every guarded endpoint answers the documented 404 ("never a guess").
        mockMvc.perform(get("/api/v1/sales").cookie(memberSession).header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound());
    }

    @Test
    void lastAdminCannotBeRevokedNorDemoted() throws Exception {
        var owner = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = owner.organizationId().toString();
        String ownerId = ownerMembershipId(owner);

        // revoke the ONLY admin -> 409 LAST_ADMIN
        mockMvc.perform(post("/api/v1/users/{id}/revoke", ownerId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        // demote the ONLY admin to a custom (non-system) role -> 409 LAST_ADMIN
        String roleId = createCustomRole(owner, "SoloLectura", "[\"sales.read\"]");
        mockMvc.perform(patch("/api/v1/users/{id}", ownerId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"roleIds\":[\"%s\"]}".formatted(roleId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void systemRolesAreImmutableAndCustomRoleRulesHold() throws Exception {
        var owner = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = owner.organizationId().toString();

        // the system role exists and is flagged
        MvcResult listed = mockMvc.perform(get("/api/v1/roles")
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].system").value(true))
                .andExpect(jsonPath("$[0].permissions.length()").value(19))
                .andReturn();
        String systemRoleId = com.jayway.jsonpath.JsonPath.read(
                listed.getResponse().getContentAsString(), "$[0].id");

        // PATCH a system role -> 409 SYSTEM_ROLE_IMMUTABLE
        mockMvc.perform(patch("/api/v1/roles/{id}", systemRoleId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_IMMUTABLE"));
        // DELETE a system role -> 409
        mockMvc.perform(delete("/api/v1/roles/{id}", systemRoleId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_IMMUTABLE"));

        // custom role lifecycle
        String roleId = createCustomRole(owner, "Auditor", "[\"sales.read\",\"fiscal.read\"]");
        mockMvc.perform(patch("/api/v1/roles/{id}", roleId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"description\":\"Lectura de ventas y fiscal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(2));

        // duplicate role name -> 409 ROLE_NAME_TAKEN
        mockMvc.perform(post("/api/v1/roles")
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"name\":\"Auditor\",\"permissions\":[\"sales.read\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));

        // invented permission code -> 400
        mockMvc.perform(post("/api/v1/roles")
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"name\":\"Raro\",\"permissions\":[\"no.existe\"]}"))
                .andExpect(status().isBadRequest());

        // in-use rule: assign the role, then DELETE -> 409 ROLE_IN_USE
        String membershipId = ownerMembershipId(owner);
        mockMvc.perform(patch("/api/v1/users/{id}", membershipId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"roleIds":["%s","%s"]}
                                """.formatted(systemRoleId, roleId)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/roles/{id}", roleId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_IN_USE"));

        // after un-assigning, the DELETE succeeds (204)
        mockMvc.perform(patch("/api/v1/users/{id}", membershipId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"roleIds\":[\"%s\"]}".formatted(systemRoleId)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/roles/{id}", roleId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isNoContent());
    }

    @Test
    void resendInvalidatesTheOldTokenAndDuplicateInviteConflicts() throws Exception {
        var owner = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = owner.organizationId().toString();
        String roleId = createCustomRole(owner, "Auxiliar", "[\"catalog.read\"]");
        String email = "reenvio-%s@example.com".formatted(UUID.randomUUID().toString().substring(0, 6));

        MvcResult invited = mockMvc.perform(post("/api/v1/users/invitations")
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"roleIds\":[\"%s\"]}".formatted(email, roleId)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstToken = com.jayway.jsonpath.JsonPath.read(
                invited.getResponse().getContentAsString(), "$.invitationToken");
        String membershipId = com.jayway.jsonpath.JsonPath.read(
                invited.getResponse().getContentAsString(), "$.member.membershipId");

        // resend: the FIRST token dies
        MvcResult resent = mockMvc.perform(post("/api/v1/users/invitations/{id}/resend", membershipId)
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andReturn();
        String secondToken = com.jayway.jsonpath.JsonPath.read(
                resent.getResponse().getContentAsString(), "$.invitationToken");
        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"M3mbro-Seguro!\"}"
                                .formatted(firstToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));

        mockMvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"Corta1!\"}"
                                .formatted(secondToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"%s\",\"password\":\"M3mbro-Seguro!\"}"
                                .formatted(secondToken)))
                .andExpect(status().isOk());

        // duplicate invite (now ACTIVE member) -> 409 MEMBER_EXISTS
        mockMvc.perform(post("/api/v1/users/invitations")
                        .cookie(sessionOf(owner))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"roleIds\":[\"%s\"]}".formatted(email, roleId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_EXISTS"));
    }

    @Test
    void foreignTenantIsInvisibleForMembers() throws Exception {
        var orgA = seedUser(UUID.randomUUID().toString().substring(0, 8));
        var orgB = seedUser(UUID.randomUUID().toString().substring(0, 8));

        // org A's owner cannot even LOOK at org B (no mirror -> no tenant)
        mockMvc.perform(get("/api/v1/users")
                        .cookie(sessionOf(orgA))
                        .header("X-Tenant-Id", orgB.organizationId().toString()))
                .andExpect(status().isNotFound());
    }
}

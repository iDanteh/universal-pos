package com.posplatform.it;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH FLOW (ADR-011): login issues a revocable DB session delivered ONLY
 * as an opaque HttpOnly cookie; tenant selection is verified server-side
 * (identity mirror + LIVE ACTIVE membership inside the tenant's RLS scope);
 * logout revokes; everything else stays fail-closed.
 */
@AutoConfigureMockMvc
class AuthFlowIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private AppUserOrganizationRepository userOrganizationRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private com.posplatform.shared.tenancy.TenantTransactionRunner tenantRunner;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(UUID userId, UUID organizationId, String email, String password) {
    }

    private Seeded seedUser(String tag) {
        String email = "owner-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org auth " + tag,
                email,                        // same email -> onboarding links the SEEDED user
                "Owner " + tag,
                "Organización auth " + tag + " SA de CV",
                null, null,
                "Sucursal Centro", "CDMX"));

        return new Seeded(user.getId(), org.organizationId(), email, password);
    }

    private static Cookie sessionCookie(MvcResult result) throws java.io.UnsupportedEncodingException {
        Cookie found = result.getResponse().getCookie("pos_session");
        assertThat(found).as("session cookie must be set on login").isNotNull();
        assertThat(found.isHttpOnly()).isTrue();
        assertThat(found.getValue()).isNotBlank();
        return found;
    }

    @Test
    void loginRejectsWrongPasswordAndUnknownEmailWithSameGenericError() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        // wrong password
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"wrong-pass\"}".formatted(seeded.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication failed"))
                .andExpect(jsonPath("$.detail").value("Credenciales inválidas"));

        // unknown email — IDENTICAL response (no user enumeration)
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"ghost-%s@example.com\",\"password\":\"x\"}".formatted(seeded.userId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Credenciales inválidas"));
    }

    @Test
    void loginIssuesSessionAndTenantClaimsAreVerifiedServerSide() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(seeded.email(), seeded.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(seeded.email()))
                .andReturn();
        Cookie session = sessionCookie(login);

        // /auth/me with the cookie: identity only, no tenant binding needed
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(seeded.email()));

        // org of the user: tenant verified via mirror + REAL ACTIVE membership (RLS)
        mockMvc.perform(get("/api/v1/organizations/current")
                        .cookie(session)
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(orgName(seeded)))
                .andExpect(jsonPath("$.verticalCode").value("CUSTOM"));

        // tenant the user does NOT belong to: refused by the filter -> endpoint 404 fail-closed
        mockMvc.perform(get("/api/v1/organizations/current")
                        .cookie(session)
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        // no tenant header at all: authenticated but tenant-less -> 404
        mockMvc.perform(get("/api/v1/organizations/current").cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutRevokesTheSessionServerSide() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(seeded.email(), seeded.password())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = sessionCookie(login);

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session))
                .andExpect(status().isNoContent());

        // the SAME raw cookie no longer authenticates: session revoked in DB
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void membershipIsTheSourceOfTruthForTenantVerification() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(seeded.email(), seeded.password())))
                .andReturn();
        Cookie session = sessionCookie(login);

        // mirror row was written by trusted onboarding
        assertThat(userOrganizationRepository
                .findByUserIdAndOrganizationId(seeded.userId(), seeded.organizationId()))
                .isPresent();

        tenantRunner.run(seeded.organizationId(), () -> {
            Membership membership = membershipRepository
                    .findByOrganizationIdAndUserId(seeded.organizationId(), seeded.userId())
                    .orElseThrow();
            membership.revoke();
            membershipRepository.save(membership);
            return null;
        });

        // the session itself stays valid...
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk());

        // ...but the revocation inside the tenant means the header claim no longer works:
        // RLS tenant verification denies EVEN with a match in the identity mirror.
        mockMvc.perform(get("/api/v1/organizations/current")
                        .cookie(session)
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isNotFound());
    }

    private String orgName(Seeded seeded) {
        return userOrganizationRepository
                .findByUserIdAndOrganizationId(seeded.userId(), seeded.organizationId())
                .orElseThrow()
                .getOrganizationName();
    }
}

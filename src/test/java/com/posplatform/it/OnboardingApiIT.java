package com.posplatform.it;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
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
 * ONBOARDING REST (ADR-012): self-service bootstrap for authenticated
 * operators. The owner is resolved SERVER-SIDE from the session user —
 * the request does not even carry owner fields, so a client can never
 * claim an owner it is not. Creating a NEW tenant needs NO X-Tenant-Id;
 * without a session everything stays fail-closed (401).
 */
@AutoConfigureMockMvc
class OnboardingApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private AppUserOrganizationRepository userOrganizationRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(String email, String password) {
    }

    private Seeded loginFreshUser(String tag) throws Exception {
        String email = "onb-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Onboarder " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("pos_session");
        assertThat(session).isNotNull();
        return new Seeded(email, password);
    }

    private UUID userIdOf(Seeded seeded) {
        return appUserRepository.findByEmailIgnoreCase(seeded.email()).orElseThrow().getId();
    }

    @Test
    void onboardingRequiresALiveSession() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .contentType("application/json")
                        .content("""
                                {"organizationName":"Org sin sesion","legalName":"Razon","branchName":"Suc"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOnboardingCreatesNewTenantOwnedByTheSessionUser() throws Exception {
        var seeded = loginFreshUser(UUID.randomUUID().toString().substring(0, 8));

        MvcResult create = mockMvc.perform(post("/api/v1/onboarding")
                        .cookie(sessionOf(seeded))
                        .contentType("application/json")
                        .content("""
                                {"organizationName":"Org via API",
                                 "legalName":"Razon social via API SA de CV",
                                 "tradeName":"Via API",
                                 "taxId":"AAA010101AAA",
                                 "branchName":"Sucursal API",
                                 "branchCity":"MTY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").isNotEmpty())
                .andExpect(jsonPath("$.legalEntityId").isNotEmpty())
                .andExpect(jsonPath("$.branchId").isNotEmpty())
                .andExpect(jsonPath("$.ownerId").value(userIdOf(seeded).toString()))
                .andExpect(jsonPath("$.ownerRoleId").isNotEmpty())
                .andExpect(jsonPath("$.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.subscriptionId").isNotEmpty())
                .andReturn();

        UUID organizationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                create.getResponse().getContentAsString(), "$.organizationId"));

        // The mirror binds THE SESSION USER to the NEW tenant (server-side owner).
        assertThat(userOrganizationRepository
                .findByUserIdAndOrganizationId(userIdOf(seeded), organizationId))
                .isPresent();

        // The new tenant is immediately usable under normal tenant verification.
        mockMvc.perform(get("/api/v1/organizations/current")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", organizationId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Org via API"));

        mockMvc.perform(get("/api/v1/organizations/current/profile")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", organizationId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CUSTOM"));
    }

    private Cookie sessionOf(Seeded seeded) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(
                                seeded.email(), seeded.password())))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getCookie("pos_session");
    }

    @Test
    void onboardingValidatesShapeBeforeCreatingAnything() throws Exception {
        var seeded = loginFreshUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/onboarding")
                        .cookie(sessionOf(seeded))
                        .contentType("application/json")
                        .content("""
                                {"organizationName":" ", "legalName":"x", "branchName":"y"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.errors.organizationName").isNotEmpty());

        mockMvc.perform(post("/api/v1/onboarding")
                        .cookie(sessionOf(seeded))
                        .contentType("application/json")
                        .content("""
                                {"organizationName":"Org sin sucursal", "legalName":"Razon valida"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.branchName").isNotEmpty());
    }

    @Test
    void clientSuppliedOwnerFieldsAreUnknownKeysAndAreIgnored() throws Exception {
        // A malicious body tries to name another owner; the request shape has
        // no owner fields at all, so the server binds the session user only.
        var seeded = loginFreshUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/onboarding")
                        .cookie(sessionOf(seeded))
                        .contentType("application/json")
                        .content("""
                                {"organizationName":"Org owner fijo",
                                 "legalName":"Razon del owner fijo SA de CV",
                                 "branchName":"Sucursal fija",
                                 "ownerEmail":"malicioso@otraorg.example"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(userIdOf(seeded).toString()));
    }
}

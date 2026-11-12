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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ORGANIZATION PROFILE REST (ADR-009/010): vertical profile read + declarative
 * switch, legal-entity growth with per-tenant RFC uniqueness. The tenant MUST
 * be verified server-side (X-Tenant-Id + live membership inside RLS): any
 * tenant-less request fails closed with 404, unknown vertical fails 400, a
 * duplicate RFC fails 409 — ProblemDetails everywhere (RFC 9457).
 */
@AutoConfigureMockMvc
class OrganizationProfileApiIT extends AbstractDatabaseIT {

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
        String email = "profile-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org profile " + tag,
                email,
                "Owner " + tag,
                "Organización profile " + tag + " SA de CV",
                null, null,                       // sin taxId: uniqueness pro-RFC se prueba con 2 entidades
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

    @Test
    void profileEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        Cookie session = sessionOf(seeded);

        // no session at all
        mockMvc.perform(get("/api/v1/organizations/current/profile"))
                .andExpect(status().isUnauthorized());

        // session without tenant header: authenticated but tenant-less -> 404
        mockMvc.perform(get("/api/v1/organizations/current/profile").cookie(session))
                .andExpect(status().isNotFound());

        // tenant the user does NOT belong to: refused by the filter -> 404
        mockMvc.perform(get("/api/v1/organizations/current/profile")
                        .cookie(session)
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void profileExposesTheEffectiveDeclarativeVertical() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/organizations/current/profile")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CUSTOM"))
                .andExpect(jsonPath("$.displayName").value("Giro abierto (base)"))
                .andExpect(jsonPath("$.baseUnit").value("PZ"))
                .andExpect(jsonPath("$.trackedDimensions").isArray())
                .andExpect(jsonPath("$.productAttributes").isArray());
    }

    @Test
    void changeVerticalSwitchesTheDeclarativeProfileAndRejectsUnknownCodes() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        mockMvc.perform(post("/api/v1/organizations/current/vertical")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"verticalCode\":\"RETAIL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("RETAIL"))
                .andExpect(jsonPath("$.displayName").value("Comercio minorista"))
                .andExpect(jsonPath("$.negativeStockPolicy").value("ALLOWED_WITH_ALERT"));

        // the change PERSISTED in the organization
        mockMvc.perform(get("/api/v1/organizations/current/profile")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("RETAIL"));

        // unknown giro: rejected by the entity AND the domain guard -> 400
        mockMvc.perform(post("/api/v1/organizations/current/vertical")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"verticalCode\":\"NO_EXISTE\"}"))
                .andExpect(status().isBadRequest());

        // blank body field -> 400 validation
        mockMvc.perform(post("/api/v1/organizations/current/vertical")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"verticalCode\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addLegalEntityCreatesAndRejectsDuplicateRfcInsideTheTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        MvcResult added = mockMvc.perform(post("/api/v1/organizations/current/legal-entities")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"legalName":"Razón social dòs SA de CV",
                                 "tradeName":"Segunda",
                                 "taxId":"AAB998877XX1",
                                 "branchName":"Sucursal Norte",
                                 "branchCity":"MTY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(tenant))
                .andExpect(jsonPath("$.legalEntityId").isNotEmpty())
                .andExpect(jsonPath("$.branchId").isNotEmpty())
                .andReturn();

        // same RFC again: business conflict -> 409 ProblemDetails
        mockMvc.perform(post("/api/v1/organizations/current/legal-entities")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"legalName":"Duplicada SA de CV", "taxId":"AAB998877XX1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Business rule conflict"))
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_DUPLICATE"));

        // malformed RFC -> 400 with the shared-domain message (ADR-006)
        mockMvc.perform(post("/api/v1/organizations/current/legal-entities")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"legalName\":\"Rfc malo SA\", \"taxId\":\"ABC123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "RFC mal formado; formato esperado: 3-4 letras + 6 dígitos + 3 caracteres (p. ej. GME800410HM2)"));

        // the listing now holds the seeding entity + the one added over REST
        String legalEntities = mockMvc.perform(get("/api/v1/organizations/current/legal-entities")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(legalEntities, "$.length()")).isEqualTo(2);
    }

    @Test
    void verticalsCatalogIsStaticAndAuthenticatedOnly() throws Exception {
        mockMvc.perform(get("/api/v1/verticals"))
                .andExpect(status().isUnauthorized());

        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(get("/api/v1/verticals").cookie(sessionOf(seeded)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verticals.length()").value(6))
                .andExpect(jsonPath("$.verticals[0].code").value("RETAIL"));
    }
}

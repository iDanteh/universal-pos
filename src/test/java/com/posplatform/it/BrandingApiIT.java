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
 * BRANDING REST (ADR-007): the effective branding walk
 * (platform defaults → brand.config → organization_brand.overrides)
 * exposed for the current tenant. Fail-closed like every tenant endpoint:
 * 401 without a session, 404 with an unverified tenant. Seeded tenants
 * without brand relations answer with platform defaults (source=platform).
 */
@AutoConfigureMockMvc
class BrandingApiIT extends AbstractDatabaseIT {

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
        String email = "brand-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org brand " + tag,
                email, "Owner " + tag,
                "Razón brand " + tag + " SA de CV",
                null, null, "Sucursal Centro", "CDMX"));
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
    void brandingFailsClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        Cookie session = sessionOf(seeded);

        mockMvc.perform(get("/api/v1/branding/effective"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/branding/effective").cookie(session))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/branding/effective")
                        .cookie(session)
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantsWithoutBrandRelationsGetPlatformDefaults() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/branding/effective")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("platform"))
                .andExpect(jsonPath("$.name").value("POS Platform"))
                .andExpect(jsonPath("$.colors.primary").exists())
                .andExpect(jsonPath("$.templates.invoice").value("standard"))
                .andExpect(jsonPath("$.featureFlags.offlineMode").value(true));
    }
}

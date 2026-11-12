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
 * CATALOG REST (fase 3 · ADR-004 + ADR-010): product + variant creation with
 * the vertical's declarative parameterization as the only rule source. The
 * tenant MUST be verified server-side (X-Tenant-Id + live membership inside
 * RLS): tenant-less or foreign requests fail closed with 404; unknown units
 * and undeclared/missing attributes fail 400; a duplicated SKU fails 409;
 * cross-tenant products are invisible (404) — ProblemDetails everywhere.
 */
@AutoConfigureMockMvc
class CatalogApiIT extends AbstractDatabaseIT {

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
        String email = "catalog-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org catálogo " + tag,
                email,
                "Owner " + tag,
                "Catálogo " + tag + " SA de CV",
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

    @Test
    void catalogEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        // no session at all
        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isUnauthorized());

        // session without tenant header: authenticated but tenant-less -> 404
        mockMvc.perform(get("/api/v1/catalog/products").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        // tenant the user does NOT belong to: refused by the filter -> 404
        mockMvc.perform(get("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unitsExposeTheCurrentVerticalParameterization() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/catalog/units")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verticalCode").value("CUSTOM"))
                .andExpect(jsonPath("$.baseUnit").value("PZ"))
                .andExpect(jsonPath("$.allowedUnits.length()").value(7))
                .andExpect(jsonPath("$.productAttributes").isEmpty());
    }

    @Test
    void createProductWithVariantsReturns201AndPersistsTheCatalog() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Cerveza Artesanal",
                                 "description":"Botella 355 ml",
                                 "category":"Bebidas",
                                 "baseUnit":"PZ",
                                 "variants":[
                                   {"sku":"cer-355","barcode":"7501234567890","unit":"PZ",
                                    "price":"28.5000"},
                                   {"sku":"cer-caja","unit":"LT","price":"52.0000"}
                                 ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cerveza Artesanal"))
                .andExpect(jsonPath("$.baseUnit").value("PZ"))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].sku").value("CER-355"))
                .andExpect(jsonPath("$.variants[0].price").value("28.5000"));

        // SKU normalized to uppercase AND persisted visible in the listing
        String listing = mockMvc.perform(get("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(listing, "$.length()")).isEqualTo(1);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(listing, "$[0].variants.length()")).isEqualTo(2);

        // duplicate SKU (case-insensitive) -> 409 ProblemDetails
        String productId = com.jayway.jsonpath.JsonPath.<String>read(listing, "$[0].id");
        mockMvc.perform(post("/api/v1/catalog/products/{productId}/variants", productId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"sku\":\"CER-355\",\"unit\":\"PZ\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Business rule conflict"))
                .andExpect(jsonPath("$.code").value("SKU_DUPLICATE"));
    }

    @Test
    void unknownUnitOrUndeclaredAttributeFailDeclaratively() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        // CUSTOM allows PZ/KG/LT/GR/MT/M2/SRV — CAJA is pharmacy/distribution only
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"name\":\"Jarabe\",\"baseUnit\":\"CAJA\"}"))
                .andExpect(status().isBadRequest());

        // variant unit outside the profile -> 400
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Jabón","baseUnit":"PZ","variants":[
                                  {"sku":"jab-001","unit":"CAJA"}]}
                                """))
                .andExpect(status().isBadRequest());

        // CUSTOM declares NO attributes: any attribute key is undeclared -> 400
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Crema","baseUnit":"PZ","variants":[
                                  {"sku":"cre-001","unit":"PZ","attributes":{"dosage":"5mg"}}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restaurantVerticalRequiresItsDeclaredAttributes() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        mockMvc.perform(post("/api/v1/organizations/current/vertical")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"verticalCode\":\"RESTAURANT\"}"))
                .andExpect(status().isOk());

        // missing required attribute `category` -> 400 (declarative guard)
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Tacos de pastor","baseUnit":"SRV","variants":[
                                  {"sku":"taco-pastor","unit":"SRV"}]}
                                """))
                .andExpect(status().isBadRequest());

        // undeclared attribute in RESTAURANT (brand is RETAIL's) -> 400
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Refresco","baseUnit":"PZ","variants":[
                                  {"sku":"ref-001","unit":"PZ",
                                   "attributes":{"category":"Bebidas","brand":"Marca"}}]}
                                """))
                .andExpect(status().isBadRequest());

        // all required attributes present -> 201
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"name":"Refresco","baseUnit":"PZ","variants":[
                                  {"sku":"ref-001","unit":"PZ",
                                   "attributes":{"category":"Bebidas"},"price":"18.0000"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].attributes.category").value("Bebidas"));
    }

    @Test
    void crossTenantProductsAreInvisible() throws Exception {
        var ownerA = seedUser(UUID.randomUUID().toString().substring(0, 8));
        var ownerB = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenantA = ownerA.organizationId().toString();
        String tenantB = ownerB.organizationId().toString();

        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(ownerA))
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("{\"name\":\"Producto de A\",\"baseUnit\":\"PZ\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        UUID productId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(body, "$.id"));

        // tenant B cannot add a variant to tenant A's product -> 404 (not 400/403)
        mockMvc.perform(post("/api/v1/catalog/products/{productId}/variants", productId)
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", tenantB)
                        .contentType("application/json")
                        .content("{\"sku\":\"rob-001\",\"unit\":\"PZ\"}"))
                .andExpect(status().isNotFound());

        // and tenant B's listing stays empty (RLS + composite FK holding)
        mockMvc.perform(get("/api/v1/catalog/products")
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", tenantB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

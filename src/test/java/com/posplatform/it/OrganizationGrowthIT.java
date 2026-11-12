package com.posplatform.it;

import com.posplatform.modules.branding.Brand;
import com.posplatform.modules.branding.OrganizationBrand;
import com.posplatform.modules.branding.application.BrandResolver;
import com.posplatform.modules.branding.application.EffectiveBranding;
import com.posplatform.modules.branding.infrastructure.BrandRepository;
import com.posplatform.modules.branding.infrastructure.OrganizationBrandRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.VerticalProfile;
import com.posplatform.modules.organization.application.AddLegalEntityCommand;
import com.posplatform.modules.organization.application.AddLegalEntityService;
import com.posplatform.modules.organization.application.DuplicateLegalEntityException;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardOrganizationResult;
import com.posplatform.modules.organization.application.OnboardingService;
import com.posplatform.modules.organization.application.OrganizationProfileService;
import com.posplatform.modules.organization.infrastructure.LegalEntityRepository;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.outbox.OutboxEventRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ORG GROWTH integration suite (ADR-009 + ADR-010 + V8):
 *
 *  - one organization accumulates N razón social (unique RFC per tenant, DB
 *    partial unique index as defense in depth),
 *  - the business vertical (giro) changes only to KNOWN declarative profiles,
 *  - white label resolves platform → brand.config → overrides recursively.
 *
 * All tenant-scoped assertions run bound to the tenant (RLS). The DB is
 * shared across IT classes, so outbox queries filter by tenant.
 */
class OrganizationGrowthIT extends AbstractDatabaseIT {

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AddLegalEntityService addLegalEntityService;
    @Autowired
    private OrganizationProfileService organizationProfileService;
    @Autowired
    private LegalEntityRepository legalEntityRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private OrganizationBrandRepository organizationBrandRepository;
    @Autowired
    private BrandResolver brandResolver;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private TenantTransactionRunner tenantRunner;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void secondLegalEntityWithBranchPersistsAndEmitsEvent() {
        OnboardOrganizationResult base = onboardingService.onboard(onboardCommand("growth"));

        var result = addLegalEntityService.add(base.organizationId(), new AddLegalEntityCommand(
                "Comercial " + tag() + " SA de CV",
                "Comercial",
                "  GME800410HM2  ",   // raw input: service normalizes (upper + trim)
                "Sucursal Norte",
                "MTY"));

        assertThat(result.organizationId()).isEqualTo(base.organizationId());
        assertThat(result.legalEntityId()).isNotNull();
        assertThat(result.branchId()).isNotNull();

        var entities = tenantRunner.run(base.organizationId(), () ->
                legalEntityRepository.findByOrganizationId(base.organizationId()));
        assertThat(entities).hasSize(2);
        assertThat(entities)
                .extracting(e -> e.getTenantId())
                .containsOnly(base.organizationId());
        // normalized RFC was persisted
        assertThat(entities)
                .extracting(e -> e.getTaxId())
                .contains("GME800410HM2");

        var events = outboxEventRepository.findPending().stream()
                .filter(event -> base.organizationId().equals(event.getTenantId()))
                .filter(event -> "organization.legal_entity.added".equals(event.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
    }

    @Test
    void duplicateRfcIsRejectedByDomainRuleAndDbIndex() {
        OnboardOrganizationResult base = onboardingService.onboard(onboardCommand("dup"));

        String rfc = "ECA1309101A5";
        addLegalEntityService.add(base.organizationId(),
                new AddLegalEntityCommand("Primera " + tag(), null, rfc, null, null));

        // 1) DOMAIN guard fails fast, before any insert (same tenant)
        assertThatThrownBy(() -> addLegalEntityService.add(base.organizationId(),
                new AddLegalEntityCommand("Segunda " + tag(), null, rfc, null, null)))
                .isInstanceOf(DuplicateLegalEntityException.class)
                .hasMessageContaining(rfc);

        // 2) DB defense in depth: a raw insert that bypasses the domain guard
        //    (case variant to enter the same unique index value) is rejected.
        assertThatThrownBy(() -> tenantRunner.run(base.organizationId(), () -> {
            var organization = organizationRepository.findById(base.organizationId()).orElseThrow();
            legalEntityRepository.save(new com.posplatform.modules.organization.LegalEntity(
                    organization, "Escalada " + tag(), null, rfc.toLowerCase()));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void malformedRfcIsRejected() {
        OnboardOrganizationResult base = onboardingService.onboard(onboardCommand("badrfc"));

        assertThatThrownBy(() -> addLegalEntityService.add(base.organizationId(),
                new AddLegalEntityCommand("Roto " + tag(), null, "12E800410HM2", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RFC mal formado");
    }

    @Test
    void verticalChangeMustUseAKnownDeclarativeProfile() {
        OnboardOrganizationResult base = onboardingService.onboard(onboardCommand("vertical"));

        var pharmacy = organizationProfileService.changeVertical(base.organizationId(), "PHARMACY");
        assertThat(pharmacy.code()).isEqualTo("PHARMACY");
        assertThat(pharmacy.trackedDimensions()).contains(VerticalProfile.Dimension.LOT, VerticalProfile.Dimension.EXPIRY);

        // persisted
        tenantRunner.run(base.organizationId(), () -> {
            Organization organization = organizationRepository.findById(base.organizationId()).orElseThrow();
            assertThat(organization.getVerticalCode()).isEqualTo("PHARMACY");
            return null;
        });

        // unknown vertical rejected by entity validation (DB CHECK is double enforcement)
        assertThatThrownBy(() -> organizationProfileService.changeVertical(base.organizationId(), "SPA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPA");

        // effective profile read requires the tenant context (RLS-scoped read)
        var profile = organizationProfileService.profileOf(base.organizationId());
        assertThat(profile.code()).isEqualTo("PHARMACY");
    }

    @Test
    void whiteLabelResolvesPlatformBrandAndOrganizationOverrides() {
        OnboardOrganizationResult base = onboardingService.onboard(onboardCommand("brand"));

        // No brand attached: pure platform defaults
        var platformOnly = brandResolver.resolve(base.organizationId());
        assertThat(platformOnly.source()).isEqualTo("platform");
        assertThat(platformOnly.name()).isEqualTo("POS Platform");

        // A brand with its own config (platform-scoped, no RLS on brand)
        var suffix = tag();
        Brand brand = brandRepository.save(new Brand("Brand " + suffix, Map.of(
                "name", "Marca " + suffix,
                "colors", Map.of("primary", "#7c3aed"))));

        // Relation + controlled overrides (tenant-scoped, save under RLS scope)
        tenantRunner.run(base.organizationId(), () -> {
            Organization organization = organizationRepository.findById(base.organizationId()).orElseThrow();
            OrganizationBrand relation = new OrganizationBrand(organization, brand, "DIRECT");
            relation.setOverrides(Map.of(
                    "colors", Map.of("primary", "#111827"),
                    "featureFlags", Map.of("offlineMode", false)));
            organizationBrandRepository.save(relation);
            return null;
        });

        EffectiveBranding effective = brandResolver.resolve(base.organizationId());
        assertThat(effective.source()).isEqualTo("organization");
        assertThat(effective.name()).isEqualTo("Marca " + suffix);          // scalar from brand tier
        assertThat(effective.colors()).containsEntry("primary", "#111827")  // override wins
                .containsEntry("surface", "#ffffff")                        // platform key survives 3 levels
                .containsEntry("text", "#1c2723");
        assertThat(effective.templates()).containsKey("invoice");           // untouched platform tier
        assertThat(effective.featureFlags()).containsEntry("offlineMode", false);
    }

    private OnboardOrganizationCommand onboardCommand(String prefix) {
        var suffix = tag();
        return new OnboardOrganizationCommand(
                "Org " + prefix + " " + suffix,
                "owner-%s-%s@example.com".formatted(prefix, suffix),
                "Owner %s".formatted(suffix),
                "Organización %s %s SA de CV".formatted(prefix, suffix),
                null,
                null,
                "Sucursal %s Centro".formatted(prefix),
                "CDMX");
    }
}

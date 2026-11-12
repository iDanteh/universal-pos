package com.posplatform.modules.organization.application;

import com.posplatform.modules.organization.Branch;
import com.posplatform.modules.organization.LegalEntity;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.infrastructure.BranchRepository;
import com.posplatform.modules.organization.infrastructure.LegalEntityRepository;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.domain.MexicanTaxId;
import com.posplatform.shared.domain.UuidV7;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.UUID;

/**
 * ORG GROWTH use case (ADR-009): add a new razón social to an EXISTING
 * organization, optionally opening its first branch, in ONE transaction:
 *
 *   legal entity (+ optional branch) + outbox domain event
 *
 * TRANSACTIONS AND RLS:
 *  - The tenant is the ORGANIZATION ITSELF (organization.id). We bind it
 *    with set_config before the first insert so RLS accepts the new rows.
 *  - One tenant may own N legal entities (V2 model); the DB CHECK
 *    tenant_id = organization_id on legal_entity keeps the graph consistent,
 *    and the V8 partial unique index keeps the RFC unique per tenant.
 *
 * VALIDATION:
 *  - Bean Validation for shape ({@link Validated}), domain rules fail fast
 *    here and DB constraints are the last line of defense.
 *  - The RFC is normalized (upper/trim) and shape-validated with the shared
 *    {@link MexicanTaxId} guard — the same rule the fiscal module will use.
 */
@Service
@Validated
public class AddLegalEntityService {

    private final OrganizationRepository organizationRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final BranchRepository branchRepository;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;

    public AddLegalEntityService(
            OrganizationRepository organizationRepository,
            LegalEntityRepository legalEntityRepository,
            BranchRepository branchRepository,
            OutboxWriter outboxWriter,
            TenantTransactionRunner tenantRunner) {
        this.organizationRepository = organizationRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.branchRepository = branchRepository;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
    }

    public record AddLegalEntityResult(UUID organizationId, UUID legalEntityId, UUID branchId) {
    }

    @Transactional
    public AddLegalEntityResult add(UUID organizationId, @Valid AddLegalEntityCommand command) {
        String normalizedTaxId = normalizeTaxId(command.taxId());

        return tenantRunner.run(organizationId, () -> {
            Organization organization = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

            if (normalizedTaxId != null && !normalizedTaxId.isBlank()
                    && legalEntityRepository.existsByOrganizationIdAndTaxIdIgnoreCase(organizationId, normalizedTaxId)) {
                throw new DuplicateLegalEntityException(
                        "El RFC " + normalizedTaxId + " ya está registrado en esta organización");
            }

            LegalEntity legalEntity = legalEntityRepository.save(new LegalEntity(
                    organization,
                    requireNotBlank(command.legalName()),
                    command.tradeName(),
                    normalizedTaxId));

            UUID branchId = null;
            if (command.branchName() != null && !command.branchName().isBlank()) {
                branchId = branchRepository.save(new Branch(
                        organizationId,
                        legalEntity,
                        command.branchName(),
                        command.branchCity())).getId();
            }

            outboxWriter.append(
                    organizationId, "organization.legal_entity.added", "legal_entity",
                    legalEntity.getId(),
                    Map.of(
                            "legalName", legalEntity.getLegalName(),
                            "taxId", legalEntity.getTaxId() == null ? "" : legalEntity.getTaxId(),
                            "vertical", organization.getVerticalCode()));

            return new AddLegalEntityResult(organizationId, legalEntity.getId(), branchId);
        });
    }

    /** Bean Validation es la primera línea; este trim garantiza datos limpios. */
    static String requireNotBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("legalName es obligatorio");
        }
        return value.trim();
    }

    /** RFC is optional but, when provided, must be well-formed (shared domain rule). */
    static String normalizeTaxId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = MexicanTaxId.normalize(raw);
        MexicanTaxId.requireValid(normalized);
        return normalized;
    }
}

package com.posplatform.modules.organization.application;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit: Bean Validation contract of the onboarding command. */
class OnboardOrganizationCommandTest {

    private final Validator validator;

    public OnboardOrganizationCommandTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    private OnboardOrganizationCommand valid() {
        return new OnboardOrganizationCommand(
                "Farmacia Central", "owner@farmacia.mx", "María López",
                "Farmacia Central SA de CV", "Farma MX", "FA123456789",
                "Sucursal Centro", "CDMX");
    }

    @Test
    void validCommandPasses() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void blankFieldsAreRejected() {
        var violations = validator.validate(new OnboardOrganizationCommand(
                " ", "owner@farmacia.mx", "María",
                "Razón Social", null, null, "Sucursal", null));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("organizationName"));
    }

    @Test
    void invalidEmailIsRejected() {
        var cmd = new OnboardOrganizationCommand(
                "Negocio", "not-an-email", "Owner",
                "Razon Social", null, null, "Branch", null);
        assertThat(validator.validate(cmd)).anyMatch(v -> v.getPropertyPath().toString().equals("ownerEmail"));
    }
}

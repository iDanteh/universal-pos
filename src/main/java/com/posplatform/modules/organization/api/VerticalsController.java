package com.posplatform.modules.organization.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Verticales catalog (ADR-010): pure static registry, safe for any
 * authenticated user (no tenant data inside). Powers the vertical switcher
 * and the onboarding form.
 */
@RestController
public class VerticalsController {

    public record VerticalsResponse(List<ProfileResponse> verticals) {
    }

    @GetMapping("/api/v1/verticals")
    public VerticalsResponse verticals() {
        return new VerticalsResponse(
                com.posplatform.modules.organization.VerticalProfile.all().stream()
                        .map(ProfileResponse::of)
                        .toList());
    }
}

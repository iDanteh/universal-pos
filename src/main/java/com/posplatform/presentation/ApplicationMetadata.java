package com.posplatform.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Immutable application metadata (version/name) resolved once at startup.
 */
@Component
public record ApplicationMetadata(
        @Value("${spring.application.name:POS Platform}") String name,
        @Value("${posplatform.version:0.1.0}") String version
) {
}

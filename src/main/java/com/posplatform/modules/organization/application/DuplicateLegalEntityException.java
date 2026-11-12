package com.posplatform.modules.organization.application;

/**
 * Domain exception for tenant-growth rules violated before persistence
 * (e.g. RFC already registered inside the same organization). Keep it a
 * FAIL-FAST domain check so the DB unique index (V8) stays a defense in
 * depth, not the primary UX for feedback.
 */
public class DuplicateLegalEntityException extends RuntimeException {

    public DuplicateLegalEntityException(String message) {
        super(message);
    }
}

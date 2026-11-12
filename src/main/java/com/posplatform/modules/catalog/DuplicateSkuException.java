package com.posplatform.modules.catalog;

/**
 * Domain exception: SKU already registered inside the tenant. Same pattern as
 * DuplicateLegalEntityException — fail-fast check over a rule whose DB unique
 * index (V10) is defense in depth, not the primary UX.
 */
public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String message) {
        super(message);
    }
}

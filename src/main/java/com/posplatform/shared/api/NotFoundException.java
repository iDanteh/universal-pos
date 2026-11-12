package com.posplatform.shared.api;

/** Entity/aggregate not found. HTTP mapping: 404. */
public class NotFoundException extends RuntimeException {

    private final String resource;
    private final String identifier;

    public NotFoundException(String resource, Object identifier) {
        super(resource + " not found with identifier '%s'".formatted(identifier));
        this.resource = resource;
        this.identifier = String.valueOf(identifier);
    }

    public String getResource() {
        return resource;
    }

    public String getIdentifier() {
        return identifier;
    }
}

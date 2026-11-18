package com.posplatform.shared.api;

/** Permission denied (ADR-020). HTTP mapping: 403 with code PERMISSION_DENIED. */
public class ForbiddenException extends RuntimeException {

    private final String code;

    public ForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

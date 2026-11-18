package com.posplatform.shared.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission codes that satisfy an endpoint (v0.10 · ADR-020).
 *
 * ANY-OF semantics: holding ANY listed code through an ACTIVE membership of
 * the current tenant allows the call; otherwise 403 PERMISSION_DENIED.
 *
 * Placement: class level annotates EVERY handler of the controller; a method
 * level annotation OVERRIDES the class level one (method wins). A METHOD
 * level annotation with an EMPTY code list means "explicitly unguarded"
 * (e.g. world-readable reference data); class-level annotations must NOT be
 * empty. Controllers without this annotation are not guarded (e.g. onboarding
 * self-service and the public auth surface).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String[] value();
}

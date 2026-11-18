package com.posplatform.shared.authz;

import com.posplatform.modules.identity.application.PermissionGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresPermission} through the PermissionGuard (v0.10 ·
 * ADR-020). Runs BEFORE the handler body: a denied request never touches a
 * repository. Method-level annotations win over class-level ones; anything
 * else (no annotation) is unguarded by design — the permission map lives on
 * the controllers themselves, next to the routes they protect.
 */
@Aspect
@Component
public class PermissionCheckAspect {

    private final PermissionGuard permissionGuard;

    public PermissionCheckAspect(PermissionGuard permissionGuard) {
        this.permissionGuard = permissionGuard;
    }

    @Around("@annotation(com.posplatform.shared.authz.RequiresPermission) "
            + "|| @within(com.posplatform.shared.authz.RequiresPermission)")
    public Object requirePermission(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequiresPermission onMethod =
                AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (onMethod != null) {
            // Method-level wins. An explicit EMPTY list means "unguarded by
            // design" (world-readable reference data) — skip the guard.
            if (onMethod.value().length == 0) {
                return joinPoint.proceed();
            }
            permissionGuard.require(onMethod.value());
            return joinPoint.proceed();
        }
        RequiresPermission onClass =
                AnnotatedElementUtils.findMergedAnnotation(
                        joinPoint.getTarget().getClass(), RequiresPermission.class);
        if (onClass == null || onClass.value().length == 0) {
            // Defensive: matched but without an effective annotation — fail CLOSED.
            throw new IllegalStateException(
                    "PermissionCheckAspect matched a handler without RequiresPermission: "
                            + joinPoint.getSignature().toShortString());
        }
        permissionGuard.require(onClass.value());
        return joinPoint.proceed();
    }
}

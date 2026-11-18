package com.posplatform.modules.identity.api;

import com.posplatform.config.SessionAuthFilter;
import com.posplatform.modules.identity.application.AuthService;
import com.posplatform.modules.identity.application.AuthService.LoginCommand;
import com.posplatform.modules.identity.application.AuthService.LoginOutcome;
import com.posplatform.modules.identity.application.AuthService.MeOutcome;
import com.posplatform.modules.identity.application.PermissionGuard;
import com.posplatform.modules.identity.application.UsersService;
import com.posplatform.modules.identity.application.UsersService.AcceptInvitationCommand;
import com.posplatform.modules.identity.application.UsersService.AcceptInvitationOutcome;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Auth REST surface (ADR-011). The session token NEVER appears in a JSON
 * body: it is set/read as an opaque HttpOnly cookie. HTTPS sets `Secure`
 * via configuration (posplatform.auth.cookie-secure).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UsersService usersService;
    private final PermissionGuard permissionGuard;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          UsersService usersService,
                          PermissionGuard permissionGuard,
                          @Value("${posplatform.auth.cookie-secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.usersService = usersService;
        this.permissionGuard = permissionGuard;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthenticatedUser> login(
            @Valid @RequestBody LoginCommand command, HttpServletResponse response) {
        LoginOutcome outcome = authService.login(command);

        setSessionCookie(response, outcome.rawToken(), (int) AuthService.SESSION_TTL.toSeconds());
        return ResponseEntity.ok(outcome.user());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.revoke(rawToken(request));
        setSessionCookie(response, null, 0);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeOutcome me(HttpServletRequest request) {
        // The filter already validated the cookie; the raw token re-check stays cheap.
        return authService.me(rawToken(request));
    }

    /**
     * v0.10 (ADR-020): PUBLIC invitation consumption. The token is the
     * credential (SHA-256 persisted, 7-day TTL) and carries its own tenant so
     * the accept can run inside the right RLS scope without any session.
     */
    public record AcceptInvitationRequest(@NotBlank String token, String password) {
    }

    @PostMapping("/invitations/accept")
    public AcceptInvitationOutcome acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return usersService.accept(new AcceptInvitationCommand(request.token(), request.password()));
    }

    /**
     * v0.10 (ADR-020): the caller's permission codes for the ACTIVE tenant —
     * UI gating reads this; the PermissionGuard stays the law server-side.
     */
    @GetMapping("/permissions")
    public Set<String> permissions() {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
        return permissionGuard.permissionsOfCurrentUser(tenantId, TenantContext.requireUserId());
    }

    static String rawToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SessionAuthFilter.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void setSessionCookie(HttpServletResponse response, String rawToken, int maxAgeSeconds) {
        String header = SessionAuthFilter.COOKIE_NAME + "=" +
                (rawToken == null ? "" : rawToken) +
                "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds +
                (cookieSecure ? "; Secure" : "");
        response.addHeader("Set-Cookie", header);
    }
}

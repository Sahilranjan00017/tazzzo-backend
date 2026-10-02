package com.tazzzo.catalog.api;

import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import com.tazzzo.admin.auth.AdminProfiles;
import com.tazzzo.catalog.api.ApiDtos.AdminMeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CMS bootstrap: who is the authenticated admin? INTERNAL surface, so {@link ApiAuthFilter} has already authenticated the
 * caller (401/403 never reach here) and, this being a GET, every role may call it. Nothing is re-authenticated here and
 * nothing is written: the response is the attached {@code AdminPrincipal} (type, id, roles) plus, for a human, the
 * allowlist's email label. It never reads the token.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMeController {

    private final AdminProfiles profiles;

    public AdminMeController(AdminProfiles profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    public AdminMeResponse me(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipalResolver.require(request);
        return new AdminMeResponse(principal.actorType().name(), principal.actorId(),
                profiles.emailLabel(principal).orElse(null), principal.roles().stream().sorted().toList());
    }
}

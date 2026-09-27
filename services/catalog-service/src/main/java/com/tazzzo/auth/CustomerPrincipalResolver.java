package com.tazzzo.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * PR-11A — the ONE canonical way to read the {@link CustomerPrincipal} {@link CustomerAuthFilter}
 * attached to a request. Every future customer controller (Profile/Address/Cart) reuses this exact
 * seam — never a scattered magic attribute string.
 */
public final class CustomerPrincipalResolver {

    /** Package-visible so only {@link CustomerAuthFilter} may set it. */
    static final String ATTRIBUTE = "com.tazzzo.auth.customer_principal";

    private CustomerPrincipalResolver() {
    }

    public static Optional<CustomerPrincipal> current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof CustomerPrincipal principal ? Optional.of(principal) : Optional.empty();
    }

    /**
     * For a controller mapped under the CUSTOMER_AUTHENTICATED surface, the principal is always
     * present by the time a controller runs (the filter rejects the request otherwise) — its
     * absence here is a wiring bug, not a legitimate anonymous state, so this fails fast.
     */
    public static CustomerPrincipal require(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new IllegalStateException(
                "customer principal missing -- CustomerAuthFilter must run before this controller"));
    }
}

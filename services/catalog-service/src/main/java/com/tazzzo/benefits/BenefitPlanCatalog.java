package com.tazzzo.benefits;

/**
 * The Benefits-owned question "is this Membership plan version configured?", asked ONLY while the static rule
 * configuration is being validated at application start. Benefits does not know how plans are defined (Membership
 * owns that); the composition layer ({@code com.tazzzo.wiring}) answers it from the already-loaded Membership plan
 * configuration, so Benefits never depends on a Membership implementation class. Never consulted at evaluation time.
 */
@FunctionalInterface
public interface BenefitPlanCatalog {

    /** {@code true} iff a Membership plan with exactly this {@code planId} AND {@code planVersion} is configured. */
    boolean contains(String planId, int planVersion);
}

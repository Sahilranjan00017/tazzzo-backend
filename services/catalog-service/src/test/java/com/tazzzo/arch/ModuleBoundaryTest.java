package com.tazzzo.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * PR-01: freezes the Phase 3.1 modular-monolith dependency graph.
 *
 * <p>The commerce domain modules (pricing, inventory, media, serviceability,
 * commerce.read, commerce.api) do NOT exist yet. Every rule uses
 * {@code allowEmptyShould(true)} so it PASSES while a module is absent and
 * begins ENFORCING the boundary the moment that module's package appears.
 * The existing {@code com.tazzzo.catalog} package is not renamed or altered.
 */
@AnalyzeClasses(packages = "com.tazzzo", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String[] OTHER_DOMAINS = {
            "com.tazzzo.pricing..",
            "com.tazzzo.inventory..",
            "com.tazzzo.media..",
            "com.tazzzo.serviceability.."
    };

    /** Catalog is a domain peer: it must not depend on other domains or on the commerce layers. */
    @ArchTest
    static final ArchRule catalog_does_not_depend_on_other_modules =
            noClasses().that().resideInAPackage("com.tazzzo.catalog..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.media..",
                            "com.tazzzo.serviceability..",
                            "com.tazzzo.commerce..")
                    .allowEmptyShould(true);

    /** Acyclic direction: no domain module may depend on the commerce.read composition layer. */
    @ArchTest
    static final ArchRule domains_do_not_depend_on_commerce_read =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.catalog..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.media..",
                            "com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.commerce.read..")
                    .allowEmptyShould(true);

    /** commerce.api composes only through commerce.read, never reaching domain internals directly. */
    @ArchTest
    static final ArchRule commerce_api_does_not_reach_domain_internals =
            noClasses().that().resideInAPackage("com.tazzzo.commerce.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.media..",
                            "com.tazzzo.serviceability..",
                            "com.tazzzo.catalog.tx..",
                            "com.tazzzo.catalog.repo..",
                            "com.tazzzo.catalog.schema..")
                    .allowEmptyShould(true);

    /** Frozen DAG direction: commerce.api -> commerce.read, NEVER the reverse (PR-07). */
    @ArchTest
    static final ArchRule commerce_read_does_not_depend_on_commerce_api =
            noClasses().that().resideInAPackage("com.tazzzo.commerce.read..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.commerce.api..")
                    .allowEmptyShould(true);

    /**
     * PR-11A — {@code auth} is a foundational security module: it must not reach into
     * {@code commerce.read} composition or any domain internals. It may depend on the shared HTTP
     * transport primitives in {@code catalog.api} (SurfaceClassifier, RequestIdFilter) — those are
     * neutral, not domain-owned.
     */
    @ArchTest
    static final ArchRule auth_does_not_depend_on_commerce_read_or_domains =
            noClasses().that().resideInAPackage("com.tazzzo.auth..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.commerce.read..",
                            "com.tazzzo.commerce.api..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.media..",
                            "com.tazzzo.serviceability..")
                    .allowEmptyShould(true);

    /**
     * PR-11A — nothing in {@code catalog}, {@code commerce} or the domain modules may depend on
     * {@code auth} internals yet. Future customer-facing controllers (Profile/Address/Cart) will
     * deliberately depend on the {@code CustomerPrincipal}/{@code CustomerPrincipalResolver}
     * contract — when that day comes this rule is the one to relax, not remove wholesale.
     */
    @ArchTest
    static final ArchRule domains_do_not_depend_on_auth =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.catalog..",
                            "com.tazzzo.commerce..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.media..",
                            "com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.auth..")
                    .allowEmptyShould(true);

    /**
     * PR-12A — {@code auth} is the foundation; {@code customer.profile} is a consumer of its
     * {@code CustomerId}/{@code CustomerPrincipal}/{@code CustomerPrincipalResolver} contract, never
     * the reverse. This is the mirror image of {@code domains_do_not_depend_on_auth} above — auth
     * must not accumulate customer-facing business data by reaching upward into a domain built on
     * top of it.
     */
    @ArchTest
    static final ArchRule auth_does_not_depend_on_customer_profile =
            noClasses().that().resideInAPackage("com.tazzzo.auth..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.profile..")
                    .allowEmptyShould(true);

    /**
     * PR-12B — the mirror image again, one layer over: {@code auth} is the foundation;
     * {@code customer.address} is a consumer of its {@code CustomerId}/{@code CustomerPrincipal}/
     * {@code CustomerIdentityAuthority} contract, never the reverse.
     */
    @ArchTest
    static final ArchRule auth_does_not_depend_on_customer_address =
            noClasses().that().resideInAPackage("com.tazzzo.auth..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.address..")
                    .allowEmptyShould(true);

    /**
     * PR-12B — {@code customer.profile} and {@code customer.address} are SIBLING domains, both
     * built on the {@code auth} foundation; neither depends on the other. Address is NOT profile.
     */
    @ArchTest
    static final ArchRule customer_profile_does_not_depend_on_customer_address =
            noClasses().that().resideInAPackage("com.tazzzo.customer.profile..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.address..")
                    .allowEmptyShould(true);

    /**
     * PR-12B — {@code customer.address} is a NEW consumer of the existing serviceability domain
     * (via {@code ServiceabilityService#resolvePublic}, the same read the public commerce
     * serviceability endpoint already uses); the dependency runs ONE way only. Serviceability core
     * must not be pulled downward into a customer-facing domain built on top of it.
     */
    @ArchTest
    static final ArchRule serviceability_does_not_depend_on_customer_address =
            noClasses().that().resideInAPackage("com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.address..")
                    .allowEmptyShould(true);

    /**
     * PR-12C — {@code customer.cart} is a consumer of the auth foundation, of the commerce.read
     * composition seam and of the address read model (for optional location context). NOTHING
     * upstream may depend on it: auth, sibling customer domains and the catalog/pricing/inventory/
     * serviceability domains are all cart-agnostic.
     */
    @ArchTest
    static final ArchRule upstream_modules_do_not_depend_on_customer_cart =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.auth..",
                            "com.tazzzo.customer.profile..",
                            "com.tazzzo.customer.address..",
                            "com.tazzzo.catalog..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.cart..")
                    .allowEmptyShould(true);

    /** PR-12C — cart is not profile: {@code customer.cart} never reaches into customer.profile. */
    @ArchTest
    static final ArchRule customer_cart_does_not_depend_on_customer_profile =
            noClasses().that().resideInAPackage("com.tazzzo.customer.cart..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.profile..")
                    .allowEmptyShould(true);

    /**
     * PR-12C — cart composes catalog/price/stock/serviceability ONLY through {@code commerce.read};
     * it never touches the domain modules directly (one buyable algorithm, one price authority).
     */
    @ArchTest
    static final ArchRule customer_cart_reads_commerce_only_through_commerce_read =
            noClasses().that().resideInAPackage("com.tazzzo.customer.cart..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..",
                            "com.tazzzo.media..")
                    .allowEmptyShould(true);

    /**
     * PR-13A — {@code customer.checkout} consumes the auth foundation, the address read model, the cart
     * read/enrichment API, the commerce.read seam (through the cart) and the shared transaction
     * primitive. NOTHING upstream may depend on it.
     */
    @ArchTest
    static final ArchRule upstream_modules_do_not_depend_on_customer_checkout =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.auth..",
                            "com.tazzzo.customer.profile..",
                            "com.tazzzo.customer.address..",
                            "com.tazzzo.customer.cart..",
                            "com.tazzzo.catalog..",
                            "com.tazzzo.commerce..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.checkout..")
                    .allowEmptyShould(true);

    /**
     * PR-13A — checkout revalidates current commerce truth ONLY through the cart/commerce.read seam;
     * it never touches the domain modules directly (one price/stock/serviceability/buyable authority),
     * is not profile, and must not depend on the future Order/Payment domains.
     */
    @ArchTest
    static final ArchRule customer_checkout_reads_commerce_only_through_the_seam_and_not_future_domains =
            noClasses().that().resideInAPackage("com.tazzzo.customer.checkout..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..",
                            "com.tazzzo.media..",
                            "com.tazzzo.customer.profile..",
                            "com.tazzzo.customer.order..",
                            "com.tazzzo.order..",
                            "com.tazzzo.payment..")
                    .allowEmptyShould(true);

    /**
     * No dependency cycles between top-level Tazzzo modules.
     *
     * <p>Dependencies INTO {@code com.tazzzo.commerce.contract} are excluded from cycle
     * analysis (PR-07): that package is the documented NEUTRAL LEAF vocabulary (see its
     * package-info) with zero outgoing dependencies, so an edge into it can never close a real
     * cycle — but naive top-level slicing would lump it with commerce.read/api and report a
     * false {@code media ↔ commerce} cycle the moment commerce.read legitimately consumes the
     * media read port. Real cycles (e.g. a domain importing commerce.read back) remain detected
     * because only edges whose TARGET is the leaf contract package are ignored.
     */
    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.tazzzo.(*)..")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                            com.tngtech.archunit.core.domain.JavaClass.Predicates
                                    .resideInAPackage("com.tazzzo.commerce.contract.."))
                    .allowEmptyShould(true);
}

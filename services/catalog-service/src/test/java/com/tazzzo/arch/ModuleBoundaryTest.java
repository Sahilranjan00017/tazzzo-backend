package com.tazzzo.arch;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.BenefitsEvaluationPort;
import com.tazzzo.benefits.BenefitsFailure;
import com.tazzzo.benefits.BenefitsEvaluationService;
import com.tazzzo.benefits.BenefitsObservability;
import com.tazzzo.benefits.BenefitsTransactionalEvaluator;
import com.tazzzo.benefits.TransactionalBenefitsEvaluationPort;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.customer.order.OrderRepository;
import com.tazzzo.customer.order.OrderService;
import com.tazzzo.customer.order.PaymentMethod;
import com.tazzzo.membership.ConfigBackedMembershipPlanSource;
import com.tazzzo.membership.MembershipBillingCalendar;
import com.tazzzo.membership.Membership;
import com.tazzzo.membership.MembershipConfig;
import com.tazzzo.membership.MembershipEntitlement;
import com.tazzzo.membership.MembershipEntitlementPort;
import com.tazzzo.membership.MembershipEntitlementReader;
import com.tazzzo.membership.MembershipEntitlementService;
import com.tazzzo.membership.MembershipFailure;
import com.tazzzo.membership.MembershipId;
import com.tazzzo.membership.MembershipObservability;
import com.tazzzo.membership.MembershipPlan;
import com.tazzzo.membership.MembershipPlanSource;
import com.tazzzo.membership.MembershipGrantReference;
import com.tazzzo.membership.MembershipRepository;
import com.tazzzo.membership.MembershipTerminationService;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import com.tazzzo.membership.MembershipPlanProperties;
import com.tazzzo.membership.MembershipService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
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

    /**
     * Commerce writes are attributed: no production class may call the actor-less price/stock write overloads (they are
     * fixture seams kept for tests). The admin HTTP layer and any future caller must pass the authenticated actor.
     */
    @ArchTest
    static final ArchRule production_code_never_writes_prices_unattributed =
            noClasses().should().callMethod(com.tazzzo.pricing.PricingService.class, "upsertPrice",
                    com.tazzzo.pricing.UpsertPriceCommand.class);

    @ArchTest
    static final ArchRule production_code_never_writes_stock_unattributed =
            noClasses().should().callMethod(com.tazzzo.inventory.InventoryService.class, "setInventory",
                    com.tazzzo.inventory.SetInventoryCommand.class);

    /** Media writes are attributed too: the actor-less set write is a fixture seam no production class may call. */
    @ArchTest
    static final ArchRule production_code_never_writes_media_unattributed =
            noClasses().should().callMethod(com.tazzzo.media.MediaService.class, "upsertMediaSet",
                    com.tazzzo.media.UpsertMediaSetCommand.class);

    /** The geo port is a leaf: it names no other module (the PIN travels as a string), so it can never close a cycle. */
    @ArchTest
    static final ArchRule location_is_a_leaf =
            noClasses().that().resideInAPackage("com.tazzzo.location..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.catalog..", "com.tazzzo.commerce..", "com.tazzzo.serviceability..",
                            "com.tazzzo.customer..", "com.tazzzo.auth..", "com.tazzzo.admin..", "com.tazzzo.account..")
                    .allowEmptyShould(true);

    /** Delivery slots sit above serviceability and never reach into commerce read/api, orders, cart or checkout (those call IN). */
    @ArchTest
    static final ArchRule delivery_does_not_depend_on_the_customer_commerce_flow =
            noClasses().that().resideInAPackage("com.tazzzo.delivery..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.commerce.read..", "com.tazzzo.commerce.api..", "com.tazzzo.customer..",
                            "com.tazzzo.membership..", "com.tazzzo.pricing..", "com.tazzzo.inventory..", "com.tazzzo.media..")
                    .allowEmptyShould(true);

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
     * PR-14A — {@code inventory} (including its reservation lifecycle) must never depend on any
     * customer-facing domain, present or future: it is a foundation-layer module, the same layer
     * as {@code pricing}/{@code serviceability}. A future {@code customer.order} may depend ON
     * {@code inventory.InventoryReservationPort}; the dependency never points the other way.
     */
    @ArchTest
    static final ArchRule inventory_does_not_depend_on_any_customer_domain =
            noClasses().that().resideInAPackage("com.tazzzo.inventory..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.customer..",
                            "com.tazzzo.order..",
                            "com.tazzzo.payment..")
                    .allowEmptyShould(true);

    /**
     * PR-14B — {@code customer.order} consumes the auth foundation, the address read model, the
     * checkout quote read model, the transactional companion ports (Pricing/Serviceability/Catalog)
     * and the Inventory reservation port. NOTHING upstream may depend on it.
     */
    @ArchTest
    static final ArchRule upstream_modules_do_not_depend_on_customer_order =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.auth..",
                            "com.tazzzo.customer.profile..",
                            "com.tazzzo.customer.address..",
                            "com.tazzzo.customer.cart..",
                            "com.tazzzo.customer.checkout..",
                            "com.tazzzo.catalog..",
                            "com.tazzzo.commerce..",
                            "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.customer.order..")
                    .allowEmptyShould(true);

    /**
     * PR-14B — {@code customer.order}'s access to normally-restricted domains is narrow: the
     * session-aware TRANSACTIONAL companion ports and their value types, and the Inventory
     * reservation port and its value types, never the concrete services/repositories/collections
     * those domains own, and never the general-purpose non-session ports (whose contract does not
     * promise session participation). This is deliberately WIDER than
     * {@code customer_cart_reads_commerce_only_through_commerce_read}/
     * {@code customer_checkout_reads_commerce_only_through_the_seam_and_not_future_domains} above:
     * Order legitimately needs direct access to pricing/serviceability/inventory for the specific
     * reason of session-aware transactional composition, not merely the composed runtime-card view
     * Cart/Checkout read through {@code commerce.read}.
     */
    @ArchTest
    static final ArchRule customer_order_does_not_depend_on_concrete_read_services =
            noClasses().that().resideInAPackage("com.tazzzo.customer.order..")
                    .should().dependOnClassesThat(
                            resideInAnyPackage("com.tazzzo.pricing..", "com.tazzzo.serviceability..",
                                    "com.tazzzo.inventory..", "com.tazzzo.media..")
                                    .and(DescribedPredicate.not(belongToAnyOf(
                                            com.tazzzo.pricing.TransactionalPriceReadPort.class,
                                            com.tazzzo.pricing.PriceLookup.class,
                                            com.tazzzo.pricing.Price.class,
                                            com.tazzzo.pricing.PriceStatus.class,
                                            com.tazzzo.serviceability.TransactionalServiceabilityReadPort.class,
                                            com.tazzzo.serviceability.ServiceabilityResolution.class,
                                            com.tazzzo.serviceability.ServiceabilityResolution.Status.class,
                                            com.tazzzo.inventory.InventoryReservationPort.class,
                                            com.tazzzo.inventory.PreparedInventoryReservation.class,
                                            com.tazzzo.inventory.InventoryReservationAllocation.class,
                                            com.tazzzo.inventory.InventoryReservation.class,
                                            com.tazzzo.inventory.InventoryReservationId.class,
                                            com.tazzzo.inventory.InventoryReservationItem.class,
                                            com.tazzzo.inventory.InventoryReservationStatus.class,
                                            com.tazzzo.inventory.InventoryReservationFailure.class,
                                            com.tazzzo.inventory.InventoryReservationFailure.Reason.class))))
                    .allowEmptyShould(true);

    /** PR-14B — Order never depends on future Payment/Admin. */
    @ArchTest
    static final ArchRule customer_order_does_not_depend_on_future_payment_or_admin =
            noClasses().that().resideInAPackage("com.tazzzo.customer.order..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.customer.payment..", "com.tazzzo.payment..", "com.tazzzo.admin..")
                    .allowEmptyShould(true);

    /**
     * PR-15A-0 — {@code customer.order} may touch the cart ONLY through the narrow session-aware
     * purchase port (and its result/integrity types): never {@code CartService}, {@code CartRepository}
     * or any cart document/DTO. The reverse direction (cart -> order) is already forbidden by
     * {@code upstream_modules_do_not_depend_on_customer_order}, which lists {@code customer.cart}.
     */
    @ArchTest
    static final ArchRule customer_order_uses_cart_only_through_the_purchase_port =
            noClasses().that().resideInAPackage("com.tazzzo.customer.order..")
                    .should().dependOnClassesThat(
                            resideInAnyPackage("com.tazzzo.customer.cart..")
                                    .and(DescribedPredicate.not(belongToAnyOf(
                                            com.tazzzo.customer.cart.CartPurchasePort.class,
                                            com.tazzzo.customer.cart.CartPurchaseOutcome.class,
                                            com.tazzzo.customer.cart.CartPurchaseIntegrityException.class))))
                    .allowEmptyShould(true);

    /**
     * PR-15A-2 — the Order HTTP layer (controller, exception handler, DTOs) depends on the Order domain
     * ({@code OrderService} and its value types) and the auth principal ONLY: never the repository,
     * Mongo, Inventory, Cart, Pricing, Serviceability, Checkout, Address, Media or any Payment package.
     * Business logic stays in the domain; the controller only delegates.
     */
    @ArchTest
    static final ArchRule customer_order_http_layer_only_delegates_to_the_order_service =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.order..")
                            .and(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto")))
                    .should().dependOnClassesThat(
                            resideInAnyPackage("com.mongodb..", "com.tazzzo.inventory..",
                                    "com.tazzzo.customer.cart..", "com.tazzzo.pricing..",
                                    "com.tazzzo.serviceability..", "com.tazzzo.customer.checkout..",
                                    "com.tazzzo.customer.address..", "com.tazzzo.media..",
                                    "com.tazzzo.customer.payment..", "com.tazzzo.payment..")
                                    .or(belongToAnyOf(OrderRepository.class)))
                    .allowEmptyShould(true);

    /**
     * PR-15A-2 — customer reachability: no HTTP class may call the internal create-only path
     * ({@code OrderService.createOrder}, which produces an intermediate {@code CREATED} Order). The only
     * customer-reachable placement is {@code placeCodOrder}.
     */
    @ArchTest
    static final ArchRule customer_order_controller_never_calls_the_create_only_path =
            noClasses().that(selfOrEnclosingSimpleNameEndingWithAny("Controller"))
                    .should().callMethod(OrderService.class, "createOrder", CustomerId.class, String.class,
                            PaymentMethod.class)
                    .allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------
    // PR-16A-1 -- com.tazzzo.membership: an independent entitlement/commercial domain, a top-level peer of
    // inventory/pricing/serviceability (its own slice), NOT under customer.*.
    // ---------------------------------------------------------------------------------------------

    private static final String MEMBERSHIP = "com.tazzzo.membership..";

    /**
     * PR-16A-1 -- Membership depends on NOTHING above it: of the auth module only the {@code CustomerId} value
     * type, of the catalog module only the shared {@code Tx} transaction primitive (the same primitive
     * {@code inventory} uses), plus {@code common.money} and plain infrastructure. Never a customer-facing
     * domain, the commerce layers, any other commerce domain, or future Benefits/Payment/Admin.
     */
    @ArchTest
    static final ArchRule membership_does_not_depend_on_other_modules =
            noClasses().that().resideInAPackage(MEMBERSHIP)
                    .should().dependOnClassesThat(
                            resideInAnyPackage("com.tazzzo.customer..", "com.tazzzo.commerce..",
                                    "com.tazzzo.pricing..", "com.tazzzo.inventory..",
                                    "com.tazzzo.serviceability..", "com.tazzzo.media..", "com.tazzzo.order..",
                                    "com.tazzzo.payment..", "com.tazzzo.benefits..", "com.tazzzo.promotion..",
                                    "com.tazzzo.admin..")
                                    .or(resideInAnyPackage("com.tazzzo.catalog..")
                                            .and(DescribedPredicate.not(belongToAnyOf(Tx.class))))
                                    .or(resideInAnyPackage("com.tazzzo.auth..")
                                            .and(DescribedPredicate.not(belongToAnyOf(CustomerId.class)))))
                    .allowEmptyShould(true);

    /** PR-16A-1 -- nothing upstream of Membership (foundation, catalog, commerce, commerce domains, every
     *  customer-facing domain incl. Order) may depend on it; consumers reach it only through a later Benefits
     *  authority. */
    @ArchTest
    static final ArchRule upstream_modules_do_not_depend_on_membership =
            noClasses().that().resideInAnyPackage(
                            "com.tazzzo.auth..", "com.tazzzo.catalog..", "com.tazzzo.commerce..",
                            "com.tazzzo.pricing..", "com.tazzzo.inventory..", "com.tazzzo.serviceability..",
                            "com.tazzzo.media..", "com.tazzzo.customer..")
                    .should().dependOnClassesThat().resideInAPackage(MEMBERSHIP)
                    .allowEmptyShould(true);

    /** PR-16A-1 -- the grant is an INTERNAL domain API for a trusted orchestrator: NO class outside the
     *  membership package may depend on {@code MembershipService}. A future allowlist is added only when a real
     *  orchestrator (Payment/Admin) exists -- this rule is the one to relax deliberately, not remove. */
    @ArchTest
    static final ArchRule nothing_outside_membership_depends_on_membership_service =
            noClasses().that().resideOutsideOfPackage(MEMBERSHIP)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipService.class)
                    .allowEmptyShould(true);

    /** PR-16A-1 -- an HTTP layer class (controller, advice, exception handler, DTO), anywhere, never depends on
     *  Membership: the future public HTTP surface must never reach the internal grant. */
    @ArchTest
    static final ArchRule http_layer_never_depends_on_membership =
            noClasses().that(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto"))
                    .or().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                    .or().areAnnotatedWith(org.springframework.stereotype.Controller.class)
                    .or().areAnnotatedWith(org.springframework.web.bind.annotation.ControllerAdvice.class)
                    .should().dependOnClassesThat().resideInAPackage(MEMBERSHIP)
                    .allowEmptyShould(true);

    /** PR-16A-1 -- Membership has no HTTP surface of its own. */
    @ArchTest
    static final ArchRule membership_has_no_http_surface =
            noClasses().that().resideInAPackage(MEMBERSHIP)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
                    .orShould().haveSimpleNameEndingWith("Controller")
                    .allowEmptyShould(true);

    /** PR-16A-1 -- {@code MembershipService} depends on the {@code MembershipPlanSource} PORT only, never on the
     *  Spring configuration/properties classes or the config-backed implementation, so a database-backed source
     *  replaces it without touching the service. */
    @ArchTest
    static final ArchRule membership_service_does_not_depend_on_spring_configuration =
            noClasses().that().belongToAnyOf(MembershipService.class)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipConfig.class,
                            MembershipPlanProperties.class, ConfigBackedMembershipPlanSource.class)
                    .orShould().dependOnClassesThat().resideInAPackage("org.springframework.boot.context.properties..")
                    .allowEmptyShould(true);

    /** PR-16A-1 -- time authority is the injected {@code Clock}: no ambient or system time anywhere in Membership. */
    @ArchTest
    static final ArchRule membership_never_reads_ambient_time =
            noClasses().that().resideInAPackage(MEMBERSHIP)
                    .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("read ambient/system time or the default zone") {
                        @Override
                        public boolean test(JavaMethodCall call) {
                            JavaClass owner = call.getTargetOwner();
                            String name = call.getName();
                            boolean now = name.equals("now") && (owner.isEquivalentTo(java.time.Instant.class)
                                    || owner.isEquivalentTo(java.time.LocalDate.class)
                                    || owner.isEquivalentTo(java.time.LocalDateTime.class)
                                    || owner.isEquivalentTo(java.time.ZonedDateTime.class)
                                    || owner.isEquivalentTo(java.time.OffsetDateTime.class)
                                    || owner.isEquivalentTo(java.time.LocalTime.class));
                            return now
                                    || (owner.isEquivalentTo(System.class) && name.equals("currentTimeMillis"))
                                    || (owner.isEquivalentTo(java.time.ZoneId.class) && name.equals("systemDefault"))
                                    || (owner.isEquivalentTo(java.util.TimeZone.class) && name.equals("getDefault"))
                                    || (owner.isEquivalentTo(java.time.Clock.class) && name.startsWith("system"));
                        }
                    })
                    .allowEmptyShould(true);

    /** PR-16A-1 -- only {@code MembershipBillingCalendar} performs Membership calendar arithmetic: no other
     *  Membership class touches a zone or a calendar date/time type. */
    @ArchTest
    static final ArchRule only_the_billing_calendar_does_calendar_arithmetic =
            noClasses().that().resideInAPackage(MEMBERSHIP)
                    .and().doNotBelongToAnyOf(MembershipBillingCalendar.class)
                    .should().dependOnClassesThat().belongToAnyOf(java.time.ZonedDateTime.class,
                            java.time.LocalDateTime.class, java.time.LocalDate.class,
                            java.time.OffsetDateTime.class, java.time.YearMonth.class, java.time.ZoneId.class,
                            java.time.ZoneOffset.class)
                    .allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------
    // PR-16A-2 -- the Membership entitlement READ seam.
    // ---------------------------------------------------------------------------------------------

    /** PR-16A-2 -- the entitlement read implementations depend on none of the future/consumer domains, the
     *  commerce domains, Admin or Spring Web. */
    @ArchTest
    static final ArchRule entitlement_read_implementations_depend_on_no_consumer_or_commerce_domain =
            noClasses().that().belongToAnyOf(MembershipEntitlementReader.class, MembershipEntitlementService.class)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.benefits..", "com.tazzzo.payment..", "com.tazzzo.order..",
                            "com.tazzzo.customer..", "com.tazzzo.commerce..", "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..", "com.tazzzo.serviceability..", "com.tazzzo.admin..",
                            "org.springframework.web..")
                    .allowEmptyShould(true);

    /** PR-16A-2 -- the session-aware implementation can emit NO metric (no observability class, no Micrometer) and
     *  can open NO transaction (no {@code Tx}, no {@code MongoClient}), because the caller's callback may retry. */
    @ArchTest
    static final ArchRule transactional_entitlement_implementation_has_no_metrics_and_no_transaction =
            noClasses().that().implement(TransactionalMembershipEntitlementPort.class)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipObservability.class, Tx.class,
                            com.mongodb.client.MongoClient.class)
                    .orShould().dependOnClassesThat().resideInAPackage("io.micrometer..")
                    .allowEmptyShould(true);

    /** PR-16A-2 -- an entitlement is the stored TERM snapshot: the read implementations never touch the plan source,
     *  the plan, the write service or the Spring configuration. */
    @ArchTest
    static final ArchRule entitlement_read_never_reinterprets_the_term_against_the_current_plan =
            noClasses().that().belongToAnyOf(MembershipEntitlementReader.class, MembershipEntitlementService.class)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipPlanSource.class, MembershipPlan.class,
                            ConfigBackedMembershipPlanSource.class, MembershipService.class, MembershipConfig.class,
                            MembershipPlanProperties.class)
                    .allowEmptyShould(true);

    /** PR-16A-2 -- READ ONLY: the entitlement read implementations never call a repository write. */
    @ArchTest
    static final ArchRule entitlement_read_implementations_never_write =
            noClasses().that().belongToAnyOf(MembershipEntitlementReader.class, MembershipEntitlementService.class)
                    .should().callMethod(MembershipRepository.class, "insert",
                            com.mongodb.client.ClientSession.class, Membership.class)
                    .orShould().callMethod(MembershipRepository.class, "expireIfDue",
                            com.mongodb.client.ClientSession.class, MembershipId.class, long.class,
                            java.time.Instant.class)
                    .allowEmptyShould(true);

    /** PR-16A-2 -- the entitlement reads use the CANDIDATE query ({@code findCurrentCandidateByCustomer}: ACTIVE or
     *  claiming an {@code openTerm}), never the grant path's {@code openTerm=true} write-slot query
     *  ({@code findOpenByCustomer}): a corrupt row that is or claims to be the current membership must reach strict
     *  reconstruction, not hide behind the partial filter and turn "empty" into "unknown". */
    @ArchTest
    static final ArchRule entitlement_read_uses_the_candidate_query_not_the_write_slot_query =
            noClasses().that().belongToAnyOf(MembershipEntitlementReader.class, MembershipEntitlementService.class)
                    .should().callMethod(MembershipRepository.class, "findOpenByCustomer", CustomerId.class)
                    .orShould().callMethod(MembershipRepository.class, "findOpenByCustomer",
                            com.mongodb.client.ClientSession.class, CustomerId.class)
                    .allowEmptyShould(true);

    /** PR-16A-2 -- the future dependency direction Benefits -> Membership, narrowly: a Benefits class may depend on
     *  Membership ONLY through the two entitlement ports and the value types below -- never the service, the
     *  repository, the Membership term, the plan, the plan source or a grant reference. Membership never depends on
     *  Benefits (see {@code membership_does_not_depend_on_other_modules}). */
    @ArchTest
    static final ArchRule benefits_reaches_membership_only_through_the_entitlement_allowlist =
            noClasses().that().resideInAPackage("com.tazzzo.benefits..")
                    .should().dependOnClassesThat(resideInAnyPackage(MEMBERSHIP)
                            .and(DescribedPredicate.not(belongToAnyOf(MembershipEntitlementPort.class,
                                    TransactionalMembershipEntitlementPort.class, MembershipEntitlement.class,
                                    MembershipId.class, MembershipFailure.class, MembershipFailure.Reason.class))))
                    .allowEmptyShould(true);

    /** PR-16A-2 -- a customer-facing controller can never reach Membership (there is still no customer capability). */
    @ArchTest
    static final ArchRule customer_controllers_cannot_access_membership =
            noClasses().that().resideInAPackage("com.tazzzo.customer..")
                    .and(selfOrEnclosingSimpleNameEndingWithAny("Controller"))
                    .should().dependOnClassesThat().resideInAPackage(MEMBERSHIP)
                    .allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------
    // PR-16A-3 -- Membership TERMINATION (cancel-at-period-end, immediate revoke): internal commands only.
    // ---------------------------------------------------------------------------------------------

    /** PR-16A-3 -- like {@code grant}, the termination commands are INTERNAL domain API for a trusted orchestrator:
     *  NO class outside the membership package may depend on {@code MembershipTerminationService}. A future allowlist
     *  is added only when a real orchestrator (Admin/Payment) exists. (HTTP-layer classes are additionally covered by
     *  {@code http_layer_never_depends_on_membership}.) */
    @ArchTest
    static final ArchRule nothing_outside_membership_depends_on_membership_termination_service =
            noClasses().that().resideOutsideOfPackage(MEMBERSHIP)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipTerminationService.class)
                    .allowEmptyShould(true);

    /** PR-16A-3 -- termination is a pure Membership lifecycle operation: it depends on no Benefits/Payment/Admin or other
     *  domain, no Spring Web (no HTTP), no plan source or configuration (a term is terminated on its own stored facts)
     *  and not on the grant service. */
    @ArchTest
    static final ArchRule membership_termination_depends_on_no_other_domain_http_or_plan_configuration =
            noClasses().that().belongToAnyOf(MembershipTerminationService.class)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tazzzo.benefits..", "com.tazzzo.payment..", "com.tazzzo.order..",
                            "com.tazzzo.customer..", "com.tazzzo.commerce..", "com.tazzzo.pricing..",
                            "com.tazzzo.inventory..", "com.tazzzo.serviceability..", "com.tazzzo.admin..",
                            "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
                    .orShould().dependOnClassesThat().belongToAnyOf(MembershipPlanSource.class, MembershipPlan.class,
                            ConfigBackedMembershipPlanSource.class, MembershipConfig.class,
                            MembershipPlanProperties.class, MembershipService.class, MembershipGrantReference.class)
                    .allowEmptyShould(true);

    /** PR-16A-3 -- metrics live in the standalone service layer ONLY: the repository and the domain/value objects have
     *  no observability or Micrometer dependency (a metric can never be emitted before commit from a CAS). */
    @ArchTest
    static final ArchRule membership_repository_and_domain_objects_emit_no_metrics =
            noClasses().that().belongToAnyOf(MembershipRepository.class, Membership.class, MembershipEntitlement.class,
                            MembershipId.class, MembershipPlan.class, MembershipGrantReference.class)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipObservability.class)
                    .orShould().dependOnClassesThat().resideInAPackage("io.micrometer..")
                    .allowEmptyShould(true);

    /** PR-16A-3 -- the entitlement READ implementations stay read only: they never call the termination CAS writes. */
    @ArchTest
    static final ArchRule entitlement_read_implementations_never_call_the_termination_writes =
            noClasses().that().belongToAnyOf(MembershipEntitlementReader.class, MembershipEntitlementService.class)
                    .should().callMethod(MembershipRepository.class, "markCancelRequested",
                            com.mongodb.client.ClientSession.class, MembershipId.class, long.class, java.time.Instant.class)
                    .orShould().callMethod(MembershipRepository.class, "markRevoked",
                            com.mongodb.client.ClientSession.class, MembershipId.class, long.class, java.time.Instant.class)
                    .allowEmptyShould(true);

    private static final String BENEFIT_VOCABULARY =
            "(?i).*(discount|bps|percent|subtotal|threshold|coupon|promo|stack).*";
    private static final String PAYMENT_VOCABULARY = "(?i).*(payment|gateway|razorpay|stripe).*";

    /** PR-16A-1 -- Membership answers entitlement, never "what it means for a cart": no discount, percentage,
     *  threshold, coupon, promotion or stacking vocabulary in any class, field or method name. */
    @ArchTest
    static final ArchRule membership_contains_no_benefit_logic_or_fields =
            noClasses().that().resideInAPackage(MEMBERSHIP).should().haveNameMatching(BENEFIT_VOCABULARY)
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule membership_fields_carry_no_benefit_vocabulary =
            noFields().that().areDeclaredInClassesThat().resideInAPackage(MEMBERSHIP)
                    .should().haveNameMatching(BENEFIT_VOCABULARY).allowEmptyShould(true);

    @ArchTest
    static final ArchRule membership_methods_carry_no_benefit_vocabulary =
            noMethods().that().areDeclaredInClassesThat().resideInAPackage(MEMBERSHIP)
                    .should().haveNameMatching(BENEFIT_VOCABULARY).allowEmptyShould(true);

    /** PR-16A-1 -- no Payment/gateway concept inside Membership (no fake payment success, no provider). */
    @ArchTest
    static final ArchRule membership_contains_no_payment_vocabulary =
            noClasses().that().resideInAPackage(MEMBERSHIP).should().haveNameMatching(PAYMENT_VOCABULARY)
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule membership_members_carry_no_payment_vocabulary =
            noFields().that().areDeclaredInClassesThat().resideInAPackage(MEMBERSHIP)
                    .should().haveNameMatching(PAYMENT_VOCABULARY).allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------
    // Benefits foundation -- the first (and only authorized) Membership consumer.
    // ---------------------------------------------------------------------------------------------

    private static final String BENEFITS = "com.tazzzo.benefits..";

    /** Benefits -- explicit freeze (the allowlist rule above already forbids it, this names the classes): Benefits
     *  never depends on a Membership IMPLEMENTATION: not the grant/termination services, the repository, the term, the
     *  plan or its source, the entitlement implementations or Membership's observability. */
    @ArchTest
    static final ArchRule benefits_does_not_depend_on_membership_implementation_classes =
            noClasses().that().resideInAPackage(BENEFITS)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipService.class,
                            MembershipTerminationService.class, MembershipRepository.class, Membership.class,
                            MembershipPlan.class, MembershipPlanSource.class, ConfigBackedMembershipPlanSource.class,
                            MembershipEntitlementService.class, MembershipEntitlementReader.class,
                            MembershipObservability.class, MembershipGrantReference.class)
                    .allowEmptyShould(true);

    /** Benefits -- the foundation is independently evaluable: it depends on no commerce, customer-facing, Payment or
     *  Admin domain, no catalog infrastructure (no {@code Tx}), no HTTP and, of auth, only {@code CustomerId}. */
    @ArchTest
    static final ArchRule benefits_depends_on_no_commerce_payment_admin_or_http =
            noClasses().that().resideInAPackage(BENEFITS)
                    .should().dependOnClassesThat(
                            resideInAnyPackage("com.tazzzo.customer..", "com.tazzzo.commerce..",
                                    "com.tazzzo.pricing..", "com.tazzzo.inventory..", "com.tazzzo.serviceability..",
                                    "com.tazzzo.media..", "com.tazzzo.order..", "com.tazzzo.payment..",
                                    "com.tazzzo.catalog..", "com.tazzzo.admin..", "com.tazzzo.promotion..",
                                    "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
                                    .or(resideInAnyPackage("com.tazzzo.auth..")
                                            .and(DescribedPredicate.not(belongToAnyOf(CustomerId.class)))))
                    .allowEmptyShould(true);

    /** Benefits -- no controller, no REST endpoint: the seam is internal. */
    @ArchTest
    static final ArchRule benefits_has_no_http_surface =
            noClasses().that().resideInAPackage(BENEFITS)
                    .should().haveSimpleNameEndingWith("Controller")
                    .orShould().beAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                    .orShould().beAnnotatedWith(org.springframework.stereotype.Controller.class)
                    .orShould().beAnnotatedWith(org.springframework.web.bind.annotation.ControllerAdvice.class)
                    .allowEmptyShould(true);

    /** Benefits -- metrics live ONLY in {@code BenefitsObservability} and its one caller, the standalone service: no
     *  other Benefits class (value types, rules, sources, evaluator, the transactional evaluator) can reach Micrometer
     *  or the observability class. */
    @ArchTest
    static final ArchRule benefits_metrics_live_only_in_the_standalone_service =
            noClasses().that().resideInAPackage(BENEFITS)
                    .and().doNotBelongToAnyOf(BenefitsObservability.class, BenefitsEvaluationService.class)
                    .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                    .orShould().dependOnClassesThat().belongToAnyOf(BenefitsObservability.class)
                    .allowEmptyShould(true);

    /** Benefits -- the transactional evaluation can emit NO metric, open NO transaction, touch NO datastore handle and
     *  never fall back to the standalone Membership read or the standalone Benefits evaluation. */
    @ArchTest
    static final ArchRule transactional_benefits_implementation_has_no_metrics_transaction_or_standalone_fallback =
            noClasses().that().implement(TransactionalBenefitsEvaluationPort.class)
                    .should().dependOnClassesThat().belongToAnyOf(BenefitsObservability.class, Tx.class,
                            com.mongodb.client.MongoClient.class, com.mongodb.client.MongoDatabase.class,
                            com.mongodb.client.MongoCollection.class, MembershipEntitlementPort.class,
                            BenefitsEvaluationPort.class, BenefitsEvaluationService.class)
                    .orShould().dependOnClassesThat().resideInAPackage("io.micrometer..")
                    .allowEmptyShould(true);

    /** Benefits -- Benefits never WRITES Membership: of the Membership package it may call only the entitlement read,
     *  the entitlement/failure accessors, the enum plumbing of {@code MembershipFailure.Reason} and (owner-qualified, and
     *  nothing else named {@code value}) {@code MembershipId.value()}. */
    @ArchTest
    static final ArchRule benefits_only_reads_membership =
            noClasses().that().resideInAPackage(BENEFITS)
                    .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("call anything in Membership but the entitlement read and accessors") {
                        private final java.util.Set<String> allowed = java.util.Set.of("currentEntitlement",
                                "membershipId", "planId", "planVersion", "validUntil", "reason", "values", "valueOf",
                                "ordinal", "name");

                        @Override
                        public boolean test(JavaMethodCall call) {
                            if (!call.getTargetOwner().getPackageName().startsWith("com.tazzzo.membership")) {
                                return false;
                            }
                            // the ONE owner-qualified exception: MembershipId.value(), the read-only accessor of an
                            // allowlisted value type, used to hand a consumer (the Order benefit snapshot) the id WITHOUT
                            // it touching Membership. A value() on ANY other Membership type is NOT authorized.
                            if (call.getName().equals("value") && call.getTargetOwner().isEquivalentTo(MembershipId.class)) {
                                return false;
                            }
                            return !allowed.contains(call.getName());
                        }
                    })
                    .allowEmptyShould(true);

    /** Benefits -- Membership entitlement has ONE authorized consumer: no class outside the Membership and Benefits
     *  packages may depend on the entitlement ports or the entitlement value, so Checkout/Order/Payment/everything else
     *  must go through Benefits. */
    @ArchTest
    static final ArchRule only_benefits_consumes_membership_entitlement =
            noClasses().that().resideOutsideOfPackages(MEMBERSHIP, BENEFITS)
                    .should().dependOnClassesThat().belongToAnyOf(MembershipEntitlementPort.class,
                            TransactionalMembershipEntitlementPort.class, MembershipEntitlement.class)
                    .allowEmptyShould(true);

    /** Benefits -- Checkout, Order and Cart can never bypass Benefits to reach Membership (explicit freeze of what
     *  {@code upstream_modules_do_not_depend_on_membership} already implies for {@code customer..}). */
    @ArchTest
    static final ArchRule checkout_order_and_cart_cannot_bypass_benefits_to_reach_membership =
            noClasses().that().resideInAnyPackage("com.tazzzo.customer.checkout..", "com.tazzzo.customer.order..",
                            "com.tazzzo.customer.cart..")
                    .should().dependOnClassesThat().resideInAPackage(MEMBERSHIP)
                    .allowEmptyShould(true);

    /** Benefits -- nothing upstream of Benefits depends on it (the dependency direction is Checkout/Order -> Benefits ->
     *  Membership, never the reverse), and Membership never depends on Benefits. */
    @ArchTest
    static final ArchRule foundation_modules_do_not_depend_on_benefits =
            noClasses().that().resideInAnyPackage("com.tazzzo.auth..", "com.tazzzo.catalog..",
                            "com.tazzzo.commerce..", "com.tazzzo.pricing..", "com.tazzzo.inventory..",
                            "com.tazzzo.serviceability..", "com.tazzzo.media..", MEMBERSHIP)
                    .should().dependOnClassesThat().resideInAPackage(BENEFITS)
                    .allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------
    // Order Benefits snapshot -- the first Benefits consumer (authoritative, inside the placement transaction).
    // ---------------------------------------------------------------------------------------------

    /** Order -- reaches Benefits ONLY through the session-aware port and the result/failure types it must read:
     *  never the standalone port, the services/evaluators, the rule source or rule, or any Benefits configuration.
     *  (Order -> Membership stays forbidden by {@code upstream_modules_do_not_depend_on_membership}.) */
    @ArchTest
    static final ArchRule order_reaches_benefits_only_through_the_transactional_port_and_result_types =
            noClasses().that().resideInAPackage("com.tazzzo.customer.order..")
                    .should().dependOnClassesThat(resideInAnyPackage("com.tazzzo.benefits..")
                            .and(DescribedPredicate.not(belongToAnyOf(TransactionalBenefitsEvaluationPort.class,
                                    BenefitEvaluation.class, BenefitEvaluation.Applied.class,
                                    BenefitEvaluation.NoBenefit.class, BenefitEvaluation.NoBenefitReason.class,
                                    BenefitsFailure.class, BenefitsFailure.Reason.class))))
                    .allowEmptyShould(true);

    /** Order -- the public HTTP surface and the Order metrics know nothing about Benefits or the snapshot: this slice
     *  is persistence/domain authority only (no DTO/OpenAPI change, no Benefits value in any metric). */
    @ArchTest
    static final ArchRule order_http_layer_and_metrics_do_not_depend_on_benefits_or_the_snapshot =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.order..")
                            .and(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto", "Observability")))
                    .should().dependOnClassesThat(resideInAnyPackage("com.tazzzo.benefits..")
                            .or(selfOrEnclosingSimpleNameStartingWithAny("OrderBenefitSnapshot")))
                    .allowEmptyShould(true);

    /** Order -- the V1 money snapshot ({@code OrderMoneySnapshot}: merchandise subtotal, benefit discount, payable) is
     *  INTERNAL: the public HTTP surface (controller, handler, DTO, INCLUDING types nested in them) and the Order metrics
     *  do not depend on it; the DTO reaches the money only through the public-safe {@code OrderMoneyView}.
     *  (Benefits, Checkout, Cart and the other upstream modules are already barred from {@code customer.order} entirely by
     *  {@code upstream_modules_do_not_depend_on_customer_order} and the Benefits boundary rules.) */
    @ArchTest
    static final ArchRule order_http_layer_and_metrics_do_not_depend_on_the_money_snapshot =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.order..")
                            .and(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto",
                                    "Observability")))
                    .should().dependOnClassesThat(selfOrEnclosingSimpleNameStartingWithAny("OrderMoneySnapshot"))
                    .allowEmptyShould(true);

    /**
     * A class whose own simple name, OR the simple name of any class enclosing it, ends with one of {@code suffixes}. A
     * nested type of an HTTP/metrics class (for example a record inside a {@code *Dto}) is part of that surface: selecting by
     * the nested type's own simple name alone would let it bypass the rule.
     */
    private static DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> selfOrEnclosingSimpleNameEndingWithAny(
            String... suffixes) {
        return new DescribedPredicate<>("self or an enclosing class has a simple name ending with "
                + java.util.Arrays.toString(suffixes)) {
            @Override
            public boolean test(com.tngtech.archunit.core.domain.JavaClass c) {
                for (java.util.Optional<com.tngtech.archunit.core.domain.JavaClass> k = java.util.Optional.of(c);
                     k.isPresent(); k = k.get().getEnclosingClass()) {
                    for (String suffix : suffixes) {
                        if (k.get().getSimpleName().endsWith(suffix)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    /**
     * A class whose own simple name, OR the simple name of any class enclosing it, starts with one of {@code prefixes}. Used
     * for dependency TARGETS: a sealed snapshot's nested records (for example {@code CheckoutBenefitSnapshot.Applied}, simple
     * name {@code Applied}) are part of that snapshot and must not slip past a simple-name prefix match.
     */
    private static DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> selfOrEnclosingSimpleNameStartingWithAny(
            String... prefixes) {
        return new DescribedPredicate<>("self or an enclosing class has a simple name starting with "
                + java.util.Arrays.toString(prefixes)) {
            @Override
            public boolean test(com.tngtech.archunit.core.domain.JavaClass c) {
                for (java.util.Optional<com.tngtech.archunit.core.domain.JavaClass> k = java.util.Optional.of(c);
                     k.isPresent(); k = k.get().getEnclosingClass()) {
                    for (String prefix : prefixes) {
                        if (k.get().getSimpleName().startsWith(prefix)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    /** Order -- Order placement never consumes Checkout's ADVISORY outputs: not the Benefits snapshot/codec/preview
     *  ({@code CheckoutBenefit*}) and not the money snapshot/codec/preview ({@code CheckoutMoney*}). The Order evaluates its
     *  own AUTHORITATIVE Benefits and money and never compares them with, or derives them from, the quote's. Legitimate
     *  quote dependencies ({@code CheckoutQuote}, its lines, {@code CheckoutQuoteId}, the quote repository) stay allowed. */
    @ArchTest
    static final ArchRule order_does_not_depend_on_checkout_advisory_benefits_or_money =
            noClasses().that().resideInAPackage("com.tazzzo.customer.order..")
                    .should().dependOnClassesThat(com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("com.tazzzo.customer.checkout..")
                            .and(selfOrEnclosingSimpleNameStartingWithAny("CheckoutMoney", "CheckoutBenefit")))
                    .allowEmptyShould(true);

    /** Checkout -- reaches Benefits ONLY through the STANDALONE port and the result/failure types it must read (the
     *  advisory snapshot projection): never the transactional port (Checkout's persistence transaction is not where
     *  Benefits is evaluated), the services/evaluators, the rule source or rule, or any Benefits configuration.
     *  (Checkout -> Membership and Checkout -> Order stay forbidden by the existing rules.) */
    @ArchTest
    static final ArchRule checkout_reaches_benefits_only_through_the_standalone_port_and_result_types =
            noClasses().that().resideInAPackage("com.tazzzo.customer.checkout..")
                    .should().dependOnClassesThat(resideInAnyPackage("com.tazzzo.benefits..")
                            .and(DescribedPredicate.not(belongToAnyOf(BenefitsEvaluationPort.class,
                                    BenefitEvaluation.class, BenefitEvaluation.Applied.class,
                                    BenefitEvaluation.NoBenefit.class, BenefitEvaluation.NoBenefitReason.class,
                                    BenefitsFailure.class, BenefitsFailure.Reason.class))))
                    .allowEmptyShould(true);

    /** Cart -- stays out of Benefits permanently. */
    @ArchTest
    static final ArchRule cart_does_not_depend_on_benefits =
            noClasses().that().resideInAPackage("com.tazzzo.customer.cart..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.benefits..")
                    .allowEmptyShould(true);

    /** Checkout -- the public HTTP surface and the Checkout metrics know nothing about Benefits or the INTERNAL snapshot:
     *  the DTO sees only the public-safe {@code CheckoutBenefitPreview} projection (no reason, no identity, no
     *  eligible subtotal), and no Benefits value reaches any metric. */
    @ArchTest
    static final ArchRule checkout_http_layer_and_metrics_do_not_depend_on_benefits_or_the_snapshot =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.checkout..")
                            .and(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto", "Observability")))
                    .should().dependOnClassesThat(resideInAnyPackage("com.tazzzo.benefits..")
                            .or(selfOrEnclosingSimpleNameStartingWithAny("CheckoutBenefitSnapshot")))
                    .allowEmptyShould(true);

    /** Checkout -- the public-safe preview projection is purely structural: it depends on no Benefits, Membership or
     *  Order class, so it can never re-evaluate Benefits, read Membership or rules, or recompute a discount. */
    @ArchTest
    static final ArchRule checkout_benefit_preview_projection_depends_on_no_benefits_membership_or_order =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.checkout..")
                            .and(selfOrEnclosingSimpleNameStartingWithAny("CheckoutBenefitPreview")))
                    .should().dependOnClassesThat().resideInAnyPackage("com.tazzzo.benefits..",
                            "com.tazzzo.membership..", "com.tazzzo.customer.order..")
                    .allowEmptyShould(false);

    /** Checkout -- no production code CREATES a quote without a Benefits snapshot: the snapshot-less
     *  {@code CheckoutQuote} constructor exists only for legacy reconstruction fixtures and tests. */
    @ArchTest
    static final ArchRule no_production_code_creates_a_checkout_quote_without_a_benefits_snapshot =
            noClasses().should().callConstructor(com.tazzzo.customer.checkout.CheckoutQuote.class,
                    String.class, long.class, String.class, long.class, java.util.List.class, int.class, long.class,
                    String.class, java.time.Instant.class, java.time.Instant.class)
                    .allowEmptyShould(true);

    /** Checkout -- no production code CREATES a quote without a money snapshot: the Benefits-only (11-argument)
     *  {@code CheckoutQuote} constructor exists only for legacy (pre-money-model) fixtures and tests. */
    @ArchTest
    static final ArchRule no_production_code_creates_a_checkout_quote_without_a_money_snapshot =
            noClasses().should().callConstructor(com.tazzzo.customer.checkout.CheckoutQuote.class,
                    String.class, long.class, String.class, long.class, java.util.List.class, int.class, long.class,
                    String.class, java.time.Instant.class, java.time.Instant.class,
                    com.tazzzo.customer.checkout.CheckoutBenefitSnapshot.class)
                    .allowEmptyShould(true);

    /** Checkout -- the public HTTP surface and the Checkout metrics never touch the persistence money snapshot or its
     *  codec: the DTO sees only the public-safe {@code CheckoutMoneyPreview} projection. */
    @ArchTest
    static final ArchRule checkout_http_layer_and_metrics_do_not_depend_on_the_money_snapshot =
            noClasses().that(com.tngtech.archunit.core.domain.JavaClass.Predicates
                            .resideInAPackage("com.tazzzo.customer.checkout..")
                            .and(selfOrEnclosingSimpleNameEndingWithAny("Controller", "ExceptionHandler", "Dto", "Observability")))
                    .should().dependOnClassesThat(selfOrEnclosingSimpleNameStartingWithAny("CheckoutMoneySnapshot"))
                    .allowEmptyShould(true);

    /** Admin audit -- the audit {@code Actor} is NEUTRAL: {@code common} (and therefore every domain that records an actor)
     *  never depends on admin authentication. An {@code AdminPrincipal} converts INTO an {@code Actor}, never the reverse. */
    @ArchTest
    static final ArchRule common_audit_never_depends_on_admin_authentication =
            noClasses().that().resideInAPackage("com.tazzzo.common..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.admin..")
                    .allowEmptyShould(true);

    /** Admin audit -- admin (internal-surface) authentication depends on neither customer authentication (separate trust
     *  domains: a customer principal can never become an admin principal) nor catalog (the HTTP layer adapts the principal
     *  into an audit actor; {@code admin.auth} stays a small, reusable leaf). */
    @ArchTest
    static final ArchRule admin_authentication_depends_on_neither_customer_auth_nor_catalog =
            noClasses().that().resideInAPackage("com.tazzzo.admin..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.auth..")
                    .orShould().dependOnClassesThat().resideInAPackage("com.tazzzo.catalog..")
                    .allowEmptyShould(false);

    @ArchTest
    static final ArchRule customer_authentication_never_depends_on_admin_authentication =
            noClasses().that().resideInAPackage("com.tazzzo.auth..")
                    .should().dependOnClassesThat().resideInAPackage("com.tazzzo.admin..")
                    .allowEmptyShould(true);

    /** Human admin OIDC -- the JOSE/JWT library is confined to admin authentication: no domain, commerce, customer-auth,
     *  catalog or audit class can parse, verify or mint a JWT with it. */
    @ArchTest
    static final ArchRule jose_jwt_library_is_confined_to_admin_authentication =
            noClasses().that().resideOutsideOfPackage("com.tazzzo.admin.auth..")
                    .should().dependOnClassesThat().resideInAPackage("com.nimbusds..")
                    .allowEmptyShould(false);

    /** Human admin OIDC -- the Google verifier, the human allowlist and their configuration are {@code admin.auth}
     *  internals. Outside it (the HTTP filter included) only the provider-neutral chain, credential and principal exist. */
    @ArchTest
    static final ArchRule google_oidc_implementation_is_internal_to_admin_authentication =
            noClasses().that().resideOutsideOfPackage("com.tazzzo.admin.auth..")
                    .should().dependOnClassesThat(selfOrEnclosingSimpleNameStartingWithAny(
                            "GoogleOidc", "HumanAdmin", "AdminAuthProperties"))
                    .allowEmptyShould(false);

    /** Human admin OIDC -- {@code AdminPrincipal} and its resolver stay PROVIDER-NEUTRAL: they never learn which credential
     *  family (shared token, Google) produced a principal, so downstream code cannot branch on it. */
    @ArchTest
    static final ArchRule admin_principal_is_provider_neutral =
            noClasses().that().haveFullyQualifiedName("com.tazzzo.admin.auth.AdminPrincipal")
                    .or().haveFullyQualifiedName("com.tazzzo.admin.auth.AdminPrincipalResolver")
                    .should().dependOnClassesThat().resideInAPackage("com.nimbusds..")
                    .orShould().dependOnClassesThat(selfOrEnclosingSimpleNameStartingWithAny(
                            "GoogleOidc", "HumanAdmin", "AdminAuthProperties", "ServiceToken", "AdminBearerCredential",
                            "AdminAuthenticat", "AdminCredentialAuthenticator"))
                    .allowEmptyShould(false);

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

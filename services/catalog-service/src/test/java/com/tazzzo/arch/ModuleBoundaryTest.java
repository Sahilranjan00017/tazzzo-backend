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

    /** No dependency cycles between top-level Tazzzo modules. */
    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.tazzzo.(*)..")
                    .should().beFreeOfCycles()
                    .allowEmptyShould(true);
}

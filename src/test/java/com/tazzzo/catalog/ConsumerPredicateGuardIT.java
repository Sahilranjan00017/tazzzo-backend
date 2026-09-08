package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard for the shared consumer-eligibility predicate (LAUNCH-CENSUS §2,
 * LIST-ELIG-1). Same posture as {@link NoBypassArchitectureIT}: the rule is structural, so it
 * cannot be satisfied by intention.
 *
 * <p>The failure being prevented is specific and silent — a launch census, a traversal probe and a
 * listing each carrying their own "almost equivalent" filter. They agree on the day they are
 * written and drift the first time the predicate changes, and both sides keep returning plausible
 * numbers while disagreeing about what a shopper can see.
 */
class ConsumerPredicateGuardIT {

    private static final Path MAIN = Path.of("src/main/java/com/tazzzo/catalog");
    private static final String OWNER = "ConsumerEligibility.java";

    /**
     * The eligibility predicate is the CONJUNCTION of lifecycle and classification.status. No file
     * but the owner may build a query that combines them: that combination IS the predicate.
     */
    @Test
    void only_ConsumerEligibility_combines_the_eligibility_axes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String name = f.getFileName().toString();
            if (OWNER.equals(name)) continue;
            String src = stripComments(Files.readString(f));
            boolean filtersLifecycle = src.contains("\"lifecycle\"")
                    && (src.contains("Filters.eq(\"lifecycle\"") || src.contains("eq(\"lifecycle\""));
            boolean filtersStatus = src.contains("Filters.eq(\"classification.status\"")
                    || src.contains("eq(\"classification.status\"");
            if (filtersLifecycle && filtersStatus) {
                violations.add(name + " builds its own lifecycle+status eligibility filter");
            }
        }
        assertThat(violations)
                .as("one definition of consumer eligibility, called by everything that needs it")
                .isEmpty();
    }

    /**
     * The ADMITTED product types are LIST-ELIG-1's decision and live with the predicate. No other
     * file may build its own collection of them.
     *
     * <p>The rule is deliberately narrower than "mentions the strings". {@code SchemaBootstrap}
     * legitimately enumerates the full domain enum {@code [single, variant_pack, bundle]} in the
     * products validator — that is the set of types that may EXIST, which is a different statement
     * from the set a shopper may SEE. What must not be duplicated is a constructed collection of
     * the admitted subset.
     */
    @Test
    void only_ConsumerEligibility_builds_the_admitted_product_type_set() throws IOException {
        List<String> constructors = List.of(
                "Set.of(\"single\"", "List.of(\"single\"",
                "Set.of(\"variant_pack\"", "List.of(\"variant_pack\"");
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String name = f.getFileName().toString();
            if (OWNER.equals(name)) continue;
            String src = stripComments(Files.readString(f)).replaceAll("\\s+", "");
            for (String ctor : constructors) {
                if (src.contains(ctor.replaceAll("\\s+", ""))) {
                    violations.add(name + " builds its own admitted-product-type collection: " + ctor);
                }
            }
        }
        assertThat(violations)
                .as("LIST-ELIG-1's admitted product_type set has exactly one home")
                .isEmpty();
    }

    /**
     * Stock is not eligibility (traversal §5.3.1). offers_current.available exists under that exact
     * name, so the predicate's owner must never reach for it.
     */
    @Test
    void the_predicate_never_consults_offer_availability() throws IOException {
        // Comments are stripped on purpose: the class DOCUMENTS why stock is excluded, and that
        // explanation is worth keeping. It is the CODE that must never reach for the field.
        String code = stripComments(Files.readString(MAIN.resolve("consumer").resolve(OWNER)));
        assertThat(code.contains("offers_current") || code.contains("\"available\""))
                .as("\"available\" is not a synonym for \"eligible\"")
                .isFalse();
    }

    private String stripComments(String src) {
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n")) {
            String code = line.trim();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

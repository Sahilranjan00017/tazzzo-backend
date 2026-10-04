package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1, structurally: no production code may delete, drop or expire price history. Every main source file that touches
 * {@code price_events} (the retained price ledger) is scanned for any delete / drop / TTL / purge operation, and the old
 * purge event and method names must be gone. (The real-database tests prove the behaviour; this stops a future helper from
 * reintroducing a delete somewhere nobody is looking.)
 */
class PriceHistoryRetentionSourceTest {

    static final Path MAIN = Path.of("src/main/java");
    static final String DESTRUCTIVE_CALLS = "deleteOne|deleteMany|findOneAndDelete|drop|dropIndex|dropIndexes|bulkWrite|expireAfter|expireAfterSeconds";
    static final Pattern DESTRUCTIVE = Pattern.compile("\\.(" + DESTRUCTIVE_CALLS + ")\\(|\"PRICE_PURGE\"|\\bpurge\\s*\\(");
    /** One statement (up to the next ';') that names the ledger collection and also performs a destructive call. */
    static final Pattern LEDGER_STATEMENT_DESTROYS = Pattern.compile("\"price_events\"[^;]*?(" + DESTRUCTIVE_CALLS + ")\\b");

    private static String code(Path f) throws IOException {
        return Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** Schema bootstrap and migrations legitimately mention many collections (and drop or expire OTHER ones); those are checked per statement. */
    private static boolean isSchemaCode(Path f) {
        String p = f.toString().replace('\\', '/');
        return p.contains("/catalog/schema/") || p.contains("/catalog/migration/");
    }

    @Test
    void no_source_that_touches_the_price_ledger_can_delete_drop_or_expire_anything() throws IOException {
        List<String> touching = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String c = code(f);
                boolean touchesLedger = c.contains("\"price_events\"") || c.contains("LEDGER");
                if (!touchesLedger) {
                    continue;
                }
                touching.add(f.getFileName().toString());
                if (isSchemaCode(f)) {
                    var st = LEDGER_STATEMENT_DESTROYS.matcher(c);
                    while (st.find()) {
                        offenders.add(f + " -> statement on price_events with " + st.group(1));
                    }
                } else {
                    var m = DESTRUCTIVE.matcher(c);
                    while (m.find()) {
                        offenders.add(f + " -> " + m.group());
                    }
                }
            }
        }
        assertThat(touching).as("the files that write or read the ledger").contains("RollupService.java", "OffersService.java", "PricingService.java", "SchemaBootstrap.java");
        assertThat(offenders).as("price history must be append-only: no delete/drop/expire/purge touches the ledger").isEmpty();
    }

    @Test
    void the_scheduler_no_longer_has_a_purge_step() throws IOException {
        String c = code(MAIN.resolve("com/tazzzo/catalog/ops/CatalogSchedulers.java"));
        assertThat(c).doesNotContain("purge").doesNotContain("PRICE_PURGE");
        assertThat(c).contains("rollupService::rollup");
    }

    @Test
    void the_ledger_destruction_detector_actually_detects() {
        // guard against the scan silently matching nothing
        String bad = "writePath.auxWrite(session, \"price_events\", e, c -> c.deleteMany(session, f));";
        assertThat(LEDGER_STATEMENT_DESTROYS.matcher(bad).find()).isTrue();
        assertThat(DESTRUCTIVE.matcher("c.deleteMany(x)").find()).isTrue();
        assertThat(DESTRUCTIVE.matcher("long purged = rollupService.purge();").find()).isTrue();
        String ok = "db.getCollection(\"price_events\").createIndex(Indexes.ascending(\"product_id\", \"ts\"));\n db.getCollection(\"x\").createIndex(k, new IndexOptions().expireAfter(1L, S));";
        assertThat(LEDGER_STATEMENT_DESTROYS.matcher(ok).find()).isFalse();
    }
}

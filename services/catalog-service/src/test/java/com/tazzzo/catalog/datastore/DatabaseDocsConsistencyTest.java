package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documentation that the database foundation relies on must not drift from the code: the retention/PII matrix covers
 * exactly the application collections, only the real TTL collections show a TTL, the staging runbook documents every
 * finding code the verifier can emit, every shipped role file and every setting, and the status document keeps the records
 * the owner asked for (audit-read COMPLETE, the open R1 conflict, the pre-existing defect).
 */
class DatabaseDocsConsistencyTest {

    static final Path DOCS = Path.of("../../docs");

    private static String read(String relative) throws IOException {
        return Files.readString(DOCS.resolve(relative));
    }

    private static List<List<String>> matrixRows(String doc) {
        List<List<String>> rows = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\| `([a-z_0-9]+)` \\|(.*)\\|$").matcher(doc);
        while (m.find()) {
            List<String> cells = new ArrayList<>();
            cells.add(m.group(1));
            for (String c : m.group(2).split("\\|")) cells.add(c.trim());
            rows.add(cells);
        }
        return rows;
    }

    @Test
    void the_retention_matrix_lists_exactly_the_application_collections_once_each() throws IOException {
        String doc = read("database/DATABASE_RETENTION_AND_PII.md");
        String matrix = doc.substring(doc.indexOf("## 2. Retention matrix"), doc.indexOf("## 3. Personal-data map"));
        List<List<String>> rows = matrixRows(matrix);
        List<String> names = rows.stream().map(r -> r.get(0)).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(new TreeSet<>(names)).isEqualTo(new TreeSet<>(SchemaBootstrap.COLLECTIONS));
        for (List<String> r : rows) {
            assertThat(r).as(r.get(0) + " has all 10 columns").hasSize(10);
            r.forEach(cell -> assertThat(cell).as(r.get(0)).isNotBlank());
        }
    }

    @Test
    void only_the_three_ttl_collections_show_a_ttl_and_no_audit_ledger_or_durable_business_collection_does() throws IOException {
        String doc = read("database/DATABASE_RETENTION_AND_PII.md");
        Set<String> withTtl = new TreeSet<>();
        for (List<String> r : matrixRows(doc.substring(doc.indexOf("## 2. Retention matrix"), doc.indexOf("## 3. Personal-data map")))) {
            if (!r.get(4).startsWith("none")) withTtl.add(r.get(0)); // columns: name, owner, class, durable, TTL, retention, ...
        }
        assertThat(withTtl).containsExactlyInAnyOrder("customer_otp_challenges", "customer_otp_verified_grants", "customer_sessions");
    }

    @Test
    void the_matrix_never_invents_a_retention_period() throws IOException {
        String doc = read("database/DATABASE_RETENTION_AND_PII.md");
        // the only numbers allowed next to a time unit are the TTL values that the indexes really carry
        Matcher m = Pattern.compile("(?i)\\b(\\d+)\\s*(days?|months?|years?)\\b").matcher(doc);
        assertThat(m.find()).as("no retention period in months/years/days is stated anywhere").isFalse();
        assertThat(doc).contains("TBD — PRODUCTION POLICY").contains("RETAINED (R1 fixed in code)")
                .as("R1 is fixed: the matrix must no longer call price_events non-compliant").doesNotContain("NON-COMPLIANT with R1");
    }

    @Test
    void every_collection_with_personal_data_in_the_summary_exists() throws IOException {
        String doc = read("database/DATABASE_RETENTION_AND_PII.md");
        Matcher m = Pattern.compile("(?m)^\\| \\*\\*(DIRECT PII|credential-derived|customer link|staff identifier)\\*\\* \\| (.*?) \\|").matcher(doc);
        int classes = 0;
        while (m.find()) {
            classes++;
            Matcher c = Pattern.compile("`([a-z_0-9]+)`").matcher(m.group(2));
            while (c.find()) assertThat(SchemaBootstrap.COLLECTIONS).as(m.group(1)).contains(c.group(1));
        }
        assertThat(classes).isEqualTo(4);
    }

    @Test
    void the_staging_runbook_documents_every_finding_code_role_file_and_setting() throws IOException {
        String runbook = read("database/DATABASE_STAGING_RUNBOOK.md");
        List<String> codes = List.of("URI_MISSING", "URI_INVALID", "TLS_REQUIRED", "TLS_VALIDATION_RELAXED", "CREDENTIALS_REQUIRED",
                "REPLICA_SET_REQUIRED", "DIRECT_CONNECTION_FORBIDDEN", "LOOPBACK_HOST_FORBIDDEN", "RETRY_WRITES_REQUIRED",
                "RETRY_READS_REQUIRED", "WRITE_CONCERN_MAJORITY_REQUIRED", "READ_CONCERN_MAJORITY_REQUIRED",
                "READ_PREFERENCE_PRIMARY_REQUIRED", "CONNECT_TIMEOUT_REQUIRED", "CONNECT_TIMEOUT_OUT_OF_RANGE",
                "SERVER_SELECTION_TIMEOUT_REQUIRED", "SERVER_SELECTION_TIMEOUT_OUT_OF_RANGE", "SOCKET_TIMEOUT_TOO_LOW",
                "WAIT_QUEUE_TIMEOUT_OUT_OF_RANGE", "POOL_SIZE_REQUIRED", "POOL_MIN_ABOVE_MAX",
                "ENVIRONMENT_NOT_IDENTIFIED", "ENVIRONMENT_UNKNOWN", "AUTHENTICATION_FAILED", "UNAUTHORIZED_COMMAND",
                "DATASTORE_UNAVAILABLE", "DATASTORE_CONFIGURATION_ERROR", "DATASTORE_CHECK_FAILED",
                "TRANSACTIONS_UNSUPPORTED_TOPOLOGY", "SESSIONS_UNSUPPORTED", "SERVER_VERSION_TOO_OLD",
                "NOT_AUTHENTICATED", "MISSING_PRIVILEGE", "EXCESS_PRIVILEGE", "OUT_OF_SCOPE_PRIVILEGE");
        for (String c : codes) assertThat(runbook).as("finding code " + c).contains(c);

        // and the reverse: every code the contract can actually emit is in the list above (so a new code forces a docs update)
        Set<String> emitted = new LinkedHashSet<>();
        for (String bad : List.of("", "not-a-uri", "mongodb://localhost:27017/x?directConnection=true&socketTimeoutMS=5&waitQueueTimeoutMS=999999&minPoolSize=9&maxPoolSize=2&tlsInsecure=true&connectTimeoutMS=1&serverSelectionTimeoutMS=99999",
                "mongodb://localhost:27017/x?replicaSet=r")) {
            ConnectionContract.evaluate(bad).forEach(v -> emitted.add(v.code()));
        }
        assertThat(codes).containsAll(emitted);

        for (String f : List.of("tazzzo-runtime.role.json", "tazzzo-migrator.role.json", "tazzzo-migration-reader.role.json")) {
            assertThat(runbook).as("role file " + f).contains(f);
            assertThat(Files.exists(DOCS.resolve("database/roles/" + f))).isTrue();
        }
        for (String setting : List.of("TAZZZO_DATASTORE_PRIVILEGE_VERIFICATION", "TAZZZO_MIGRATION_ENVIRONMENT", "MONGODB_URI",
                "TAZZZO_SCHEDULER_ENABLED", "tazzzo_staging", "/tazzzo/staging/backend/mongodb-uri", "/tazzzo/staging/migration/mongodb-uri")) {
            assertThat(runbook).as(setting).contains(setting);
        }
    }

    @Test
    void the_privilege_verification_setting_is_wired_with_the_documented_default() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("privilege-verification: ${TAZZZO_DATASTORE_PRIVILEGE_VERIFICATION:AUTO}");
        assertThat(new DatastoreProperties().getPrivilegeVerification()).isEqualTo(DatastoreProperties.Verification.AUTO);
    }

    @Test
    void the_status_document_keeps_the_records_the_owner_asked_for() throws IOException {
        String status = read("ENGINEERING_STATUS.md");
        assertThat(status).contains("**COMPLETE** (PR #49, squash `822728694cb5dd80a5b68c4587c6c79911222adc`")
                .contains("37138053909")
                .contains("R1 — FIXED IN CODE")
                .contains("PRE-EXISTING DEFECT — OUTSIDE THE DATABASE FOUNDATION")
                .contains("GET /api/v1/products");
        assertThat(status).as("the six deployment gates stay unverified").contains("The six deployment gates stay **PENDING / UNVERIFIED**");
        assertThat(status).as("no production-ready claim").contains("The production datastore is NOT READY");
    }
}

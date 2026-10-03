package com.tazzzo.catalog.datastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The explicit connection contract (DB-4 / R7): pure URI evaluation, no connection. */
class ConnectionContractTest {

    static final String OPTIONS = "retryWrites=true&retryReads=true&w=majority&readConcernLevel=majority&readPreference=primary"
            + "&connectTimeoutMS=10000&serverSelectionTimeoutMS=15000&maxPoolSize=20";
    static final String SRV = "mongodb+srv://appuser:s3cretPW@cluster0.abcde.mongodb.net/tazzzo_staging?" + OPTIONS;
    static final String RS = "mongodb://appuser:s3cretPW@h1.db.example.net:27017,h2.db.example.net:27017/tazzzo_staging?replicaSet=rs0&tls=true&" + OPTIONS;

    private static List<String> codes(String uri) {
        return ConnectionContract.evaluate(uri).stream().map(Violation::code).toList();
    }

    @Test
    void a_fully_stated_srv_and_replica_set_uri_satisfies_the_contract() {
        assertThat(ConnectionContract.evaluate(SRV)).isEmpty();
        assertThat(ConnectionContract.evaluate(RS)).isEmpty();
    }

    @Test
    void the_application_default_uri_violates_it_in_every_way_that_matters() {
        List<String> c = codes("mongodb://localhost:27017/tazzzo?replicaSet=rs0");
        assertThat(c).contains("TLS_REQUIRED", "CREDENTIALS_REQUIRED", "LOOPBACK_HOST_FORBIDDEN", "RETRY_WRITES_REQUIRED",
                "RETRY_READS_REQUIRED", "WRITE_CONCERN_MAJORITY_REQUIRED", "READ_CONCERN_MAJORITY_REQUIRED",
                "READ_PREFERENCE_PRIMARY_REQUIRED", "CONNECT_TIMEOUT_REQUIRED", "SERVER_SELECTION_TIMEOUT_REQUIRED", "POOL_SIZE_REQUIRED");
        assertThat(c).doesNotContain("REPLICA_SET_REQUIRED");
    }

    @ParameterizedTest(name = "dropping [{0}] -> {1}")
    @CsvSource(delimiter = '|', value = {
            "retryWrites=true&|RETRY_WRITES_REQUIRED",
            "retryReads=true&|RETRY_READS_REQUIRED",
            "w=majority&|WRITE_CONCERN_MAJORITY_REQUIRED",
            "readConcernLevel=majority&|READ_CONCERN_MAJORITY_REQUIRED",
            "readPreference=primary&|READ_PREFERENCE_PRIMARY_REQUIRED",
            "&connectTimeoutMS=10000|CONNECT_TIMEOUT_REQUIRED",
            "&serverSelectionTimeoutMS=15000|SERVER_SELECTION_TIMEOUT_REQUIRED",
            "&maxPoolSize=20|POOL_SIZE_REQUIRED"})
    void every_required_option_must_be_stated_explicitly(String fragment, String code) {
        String without = RS.replace(fragment, "");
        assertThat(without).isNotEqualTo(RS);
        assertThat(codes(without)).containsExactly(code);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "retryWrites=true|retryWrites=false|RETRY_WRITES_REQUIRED",
            "retryReads=true|retryReads=false|RETRY_READS_REQUIRED",
            "w=majority|w=1|WRITE_CONCERN_MAJORITY_REQUIRED",
            "w=majority|w=2|WRITE_CONCERN_MAJORITY_REQUIRED",
            "readConcernLevel=majority|readConcernLevel=local|READ_CONCERN_MAJORITY_REQUIRED",
            "readConcernLevel=majority|readConcernLevel=available|READ_CONCERN_MAJORITY_REQUIRED",
            "readPreference=primary|readPreference=secondary|READ_PREFERENCE_PRIMARY_REQUIRED",
            "readPreference=primary|readPreference=primaryPreferred|READ_PREFERENCE_PRIMARY_REQUIRED",
            "connectTimeoutMS=10000|connectTimeoutMS=999|CONNECT_TIMEOUT_OUT_OF_RANGE",
            "connectTimeoutMS=10000|connectTimeoutMS=15001|CONNECT_TIMEOUT_OUT_OF_RANGE",
            "serverSelectionTimeoutMS=15000|serverSelectionTimeoutMS=30001|SERVER_SELECTION_TIMEOUT_OUT_OF_RANGE",
            "serverSelectionTimeoutMS=15000|serverSelectionTimeoutMS=500|SERVER_SELECTION_TIMEOUT_OUT_OF_RANGE",
            "maxPoolSize=20|maxPoolSize=101|POOL_SIZE_REQUIRED",
            "maxPoolSize=20|maxPoolSize=0|POOL_SIZE_REQUIRED",
            "tls=true|tls=false|TLS_REQUIRED",
            "tls=true|tls=true&tlsInsecure=true|TLS_VALIDATION_RELAXED",
            "tls=true|tls=true&tlsAllowInvalidCertificates=true|TLS_VALIDATION_RELAXED",
            "tls=true|tls=true&tlsAllowInvalidHostnames=true|TLS_VALIDATION_RELAXED",
            "tls=true|tls=true&TLSALLOWINVALIDCERTIFICATES=TRUE|TLS_VALIDATION_RELAXED",
            "tls=true|tls=true&tlsAllowInvalidHostnames=true|TLS_VALIDATION_RELAXED"})
    void a_wrong_value_is_refused_not_just_a_missing_one(String from, String to, String code) {
        String bad = RS.replace(from, to);
        assertThat(bad).isNotEqualTo(RS);
        assertThat(codes(bad)).contains(code);
    }

    @Test
    void direct_connection_is_refused_and_is_itself_invalid_with_several_hosts() {
        String single = "mongodb://appuser:s3cretPW@h1.db.example.net:27017/tazzzo_staging?directConnection=true&tls=true&" + OPTIONS;
        assertThat(codes(single)).contains("DIRECT_CONNECTION_FORBIDDEN");
        // the driver itself rejects directConnection with several seeds; that is a URI_INVALID finding, not an exception
        assertThat(codes(RS + "&directConnection=true")).containsExactly("URI_INVALID");
    }

    @Test
    void socket_timeout_is_optional_but_a_tight_one_is_refused() {
        assertThat(ConnectionContract.evaluate(RS + "&socketTimeoutMS=60000")).isEmpty();
        assertThat(ConnectionContract.evaluate(RS + "&socketTimeoutMS=0")).isEmpty();
        assertThat(codes(RS + "&socketTimeoutMS=5000")).containsExactly("SOCKET_TIMEOUT_TOO_LOW");
    }

    @Test
    void wait_queue_timeout_is_optional_but_bounded() {
        assertThat(ConnectionContract.evaluate(RS + "&waitQueueTimeoutMS=30000")).isEmpty();
        assertThat(codes(RS + "&waitQueueTimeoutMS=120000")).containsExactly("WAIT_QUEUE_TIMEOUT_OUT_OF_RANGE");
    }

    @Test
    void min_pool_above_max_pool_is_refused() {
        assertThat(codes(RS + "&minPoolSize=30")).containsExactly("POOL_MIN_ABOVE_MAX");
        assertThat(ConnectionContract.evaluate(RS + "&minPoolSize=5")).isEmpty();
    }

    @Test
    void credentials_a_replica_set_and_a_remote_host_are_required() {
        assertThat(codes(RS.replace("appuser:s3cretPW@", ""))).containsExactly("CREDENTIALS_REQUIRED");
        assertThat(codes(RS.replace("replicaSet=rs0&", ""))).containsExactly("REPLICA_SET_REQUIRED");
        assertThat(codes(RS.replace("h1.db.example.net:27017,h2.db.example.net:27017", "localhost:27017"))).containsExactly("LOOPBACK_HOST_FORBIDDEN");
        assertThat(codes(RS.replace("h1.db.example.net", "127.0.0.1"))).containsExactly("LOOPBACK_HOST_FORBIDDEN");
        assertThat(codes(RS.replace("h1.db.example.net", "[::1]"))).containsExactly("LOOPBACK_HOST_FORBIDDEN");
        assertThat(codes(RS.replace("h1.db.example.net", "0.0.0.0"))).containsExactly("LOOPBACK_HOST_FORBIDDEN");
    }

    @Test
    void srv_implies_tls_unless_it_is_switched_off() {
        assertThat(codes(SRV + "&tls=false")).containsExactly("TLS_REQUIRED");
    }

    @Test
    void missing_or_invalid_uris_are_findings_not_exceptions() {
        assertThat(codes(null)).containsExactly("URI_MISSING");
        assertThat(codes("   ")).containsExactly("URI_MISSING");
        assertThat(codes("not-a-uri")).containsExactly("URI_INVALID");
        assertThat(codes("mongodb://")).containsExactly("URI_INVALID");
    }

    @Test
    void no_finding_ever_contains_a_host_user_password_or_the_uri() {
        for (String uri : List.of(RS.replace("tls=true", "tls=false").replace("w=majority", "w=1"),
                "mongodb://appuser:s3cretPW@h1.db.example.net:27017/tazzzo_staging",
                SRV.replace("retryWrites=true", "retryWrites=false"))) {
            for (Violation v : ConnectionContract.evaluate(uri)) {
                String text = v.toString();
                assertThat(text).doesNotContain("s3cretPW").doesNotContain("appuser").doesNotContain("example.net")
                        .doesNotContain("mongodb.net").doesNotContain("mongodb://").doesNotContain("mongodb+srv://");
            }
        }
    }
}

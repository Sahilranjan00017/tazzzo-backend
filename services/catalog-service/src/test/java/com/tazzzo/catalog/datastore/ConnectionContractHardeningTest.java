package com.tazzzo.catalog.datastore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static com.tazzzo.catalog.datastore.ConnectionContractTest.OPTIONS;
import static com.tazzzo.catalog.datastore.ConnectionContractTest.RS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-4 hardening of the connection contract: settings that silently weaken what the required options promise
 * (journal, wtimeout, revocation checking, proxy, blank replica set) and the guarantee that an unparseable
 * connection string never echoes the string, its credentials or its host.
 */
class ConnectionContractHardeningTest {

    private static List<String> codes(String uri) {
        return ConnectionContract.evaluate(uri).stream().map(Violation::code).toList();
    }

    private static String with(String extra) {
        return RS + "&" + extra;
    }

    @Test
    void a_blank_replica_set_name_is_not_a_replica_set() {
        String blank = "mongodb://appuser:s3cretPW@h1.db.example.net:27017/tazzzo_staging?replicaSet=&tls=true&" + OPTIONS;
        assertThat(codes(blank)).containsExactly("REPLICA_SET_REQUIRED");
    }

    @Test
    void journal_false_is_refused_and_journal_true_or_unset_is_not() {
        assertThat(codes(with("journal=false"))).containsExactly("JOURNAL_DISABLED_FORBIDDEN");
        assertThat(codes(with("journal=true"))).isEmpty();
        assertThat(codes(RS)).isEmpty();
    }

    @ParameterizedTest(name = "wtimeoutMS={0} -> {1}")
    @CsvSource(delimiter = '|', value = {"1|WTIMEOUT_TOO_LOW", "500|WTIMEOUT_TOO_LOW", "999|WTIMEOUT_TOO_LOW",
            "0|-", "1000|-", "5000|-", "60000|-"})
    void a_tiny_wtimeout_is_refused_but_no_limit_and_sane_values_are_allowed(String ms, String expected) {
        List<String> c = codes(with("wtimeoutMS=" + ms));
        if (expected.equals("-")) {
            assertThat(c).isEmpty();
        } else {
            assertThat(c).containsExactly(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tlsDisableOCSPEndpointCheck=true", "TLSDISABLEOCSPENDPOINTCHECK=TRUE",
            "tlsDisableCertificateRevocationCheck=true"})
    void switching_certificate_revocation_checking_off_is_refused(String option) {
        assertThat(codes(with(option))).contains("TLS_REVOCATION_CHECK_DISABLED");
    }

    @Test
    void revocation_checking_left_on_is_accepted() {
        assertThat(codes(with("tlsDisableOCSPEndpointCheck=false"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"proxyHost=proxy.example.net", "proxyHost=proxy.example.net&proxyPort=1080",
            "proxyHost=proxy.example.net&proxyPort=1080&proxyUsername=u&proxyPassword=p", "PROXYHOST=proxy.example.net"})
    void any_proxy_option_is_refused_because_it_changes_the_route_to_the_datastore(String option) {
        assertThat(codes(with(option))).contains("PROXY_FORBIDDEN");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"w=majority;proxyHost=evil.example.net", "retryWrites=true;proxyHost=evil.example.net;proxyPort=1080",
            "proxyHost=evil.example.net;w=majority", "a=1&w=majority;PROXYHOST=evil.example.net&retryReads=true",
            "w=majority;proxyHost=evil.example.net;proxyUsername=u;proxyPassword=p", "maxPoolSize=20;ProxyHost=evil.example.net"})
    void a_proxy_behind_a_semicolon_delimiter_is_refused_exactly_like_one_behind_an_ampersand(String option) {
        // the MongoDB driver accepts ';' as well as '&' between options and applies every proxy option it reads
        assertThat(codes(with(option))).contains("PROXY_FORBIDDEN");
        assertThat(codes(RS.replace("&maxPoolSize=20", "") + ";" + option)).as("appended with ';'").contains("PROXY_FORBIDDEN");
    }

    @Test
    void the_semicolon_proxy_is_forbidden_in_the_staging_contract_and_clean_semicolon_options_are_not() {
        assertThat(codes(RS + ";proxyHost=evil.example.net")).contains("PROXY_FORBIDDEN");
        assertThat(codes(RS + ";journal=true")).isEmpty();
    }

    @Test
    void a_proxy_option_the_driver_cannot_even_parse_is_still_a_refusal() {
        assertThat(codes(with("proxyPort=1080"))).isNotEmpty(); // PROXY_FORBIDDEN or URI_INVALID, never accepted
        assertThat(codes(with("proxyPassword=p"))).isNotEmpty();
    }

    // ---- URI_INVALID never echoes anything it was given ----

    static final String PASSWORD = "Sup3rSecretPW";
    static final String USER = "svc_account_user";
    static final String HOST = "private-db-host.corp.example.net";

    static final List<String> INVALID = List.of(
            "mongodb://" + USER + ":" + PASSWORD + "@" + HOST + ":27017/tazzzo?replicaSet=rs0&maxPoolSize=notanumber",
            "mongodb://" + USER + ":" + PASSWORD + "@" + HOST + ":99999999/tazzzo?replicaSet=rs0",
            "mongodb://" + USER + ":Sup3rSecret%ZZPW@" + HOST + ":27017/tazzzo?replicaSet=rs0",
            "mongodb://" + USER + ":" + PASSWORD + "@" + HOST + "/tazzzo?replicaSet=rs0&w=majority&readPreference=bogus",
            "mongodb+srv://" + USER + ":" + PASSWORD + "@" + HOST + ":27017/tazzzo");

    @Test
    void every_unparseable_uri_is_a_fixed_message_without_the_uri_the_credentials_or_the_host() {
        for (String uri : INVALID) {
            List<Violation> v = ConnectionContract.evaluate(uri);
            assertThat(v).as(uri).extracting(Violation::code).as(uri).contains("URI_INVALID");
            String rendered = v.toString();
            assertThat(rendered).as(uri).doesNotContain(PASSWORD).doesNotContain("Sup3rSecret").doesNotContain(USER)
                    .doesNotContain(HOST).doesNotContain(uri).doesNotContain("27017").doesNotContain("mongodb://");
            v.forEach(f -> assertThat(f.message()).isEqualTo("the MongoDB connection string is not valid"));
        }
    }

    @Test
    void a_refused_startup_on_an_unparseable_uri_leaks_nothing_in_the_exception_or_the_logs() {
        Logger verifierLog = (Logger) LoggerFactory.getLogger(DatastoreStartupVerifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level before = verifierLog.getLevel();
        verifierLog.setLevel(Level.ALL);
        verifierLog.addAppender(appender);
        try {
            for (String uri : INVALID) {
                MigrationProperties m = new MigrationProperties();
                m.setEnvironment("staging");
                m.setMode(MigrationMode.VERIFY);
                DatastoreStartupVerifier verifier = new DatastoreStartupVerifier(null, "tazzzo_staging", m,
                        new DatastoreProperties(), uri, SchemaBootstrap.COLLECTIONS);
                assertThatThrownBy(() -> verifier.run(new DefaultApplicationArguments())).as(uri)
                        .isInstanceOf(DatastoreContractException.class)
                        .satisfies(e -> {
                            assertThat(e.getMessage()).contains("URI_INVALID");
                            assertThat(e.getMessage()).doesNotContain(PASSWORD).doesNotContain("Sup3rSecret").doesNotContain(USER)
                                    .doesNotContain(HOST).doesNotContain(uri);
                            assertThat(e.getCause()).as("no driver exception is chained: its message may echo the string").isNull();
                        });
            }
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(msg -> assertThat(msg).doesNotContain(PASSWORD).doesNotContain(USER).doesNotContain(HOST));
        } finally {
            verifierLog.detachAppender(appender);
            verifierLog.setLevel(before);
        }
    }
}

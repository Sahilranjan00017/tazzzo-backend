package com.tazzzo.catalog.migration;

import com.tazzzo.catalog.datastore.ConnectionContract;

import java.util.List;

/**
 * Who is migrating what, where. Never contains a connection string or credentials.
 *
 * <p>{@code local} is the ONE authoritative local-target decision ({@link ConnectionContract#isLocalTarget}): every host a
 * provable loopback and no proxy. The 5-argument constructor derives it from the host list alone (no proxy information);
 * {@link #forUri} derives it from the connection string, which sees proxy options. {@code TargetGuard} uses only this flag.
 */
public record MigrationTarget(String environment, String database, List<String> hosts, String operator,
                              String buildVersion, boolean local) {

    public MigrationTarget(String environment, String database, List<String> hosts, String operator, String buildVersion) {
        this(environment, database, hosts, operator, buildVersion, hostsAreLocal(hosts));
    }

    /** The target as the connection string defines it (falls back to the host list when the string is blank). */
    public static MigrationTarget forUri(String environment, String database, List<String> hosts, String uri,
                                         String operator, String buildVersion) {
        boolean local = uri == null || uri.isBlank() ? hostsAreLocal(hosts) : ConnectionContract.isLocalTarget(uri);
        return new MigrationTarget(environment, database, hosts, operator, buildVersion, local);
    }

    /** Every host a provable loopback (an empty or null list is NOT local: fail closed). */
    static boolean hostsAreLocal(List<String> hosts) {
        return hosts != null && !hosts.isEmpty() && hosts.stream().allMatch(ConnectionContract::isLoopback);
    }

    public String describe() {
        return "environment=" + (environment == null || environment.isBlank() ? "UNSPECIFIED" : environment)
                + " database=" + database + " hosts=" + hosts + " operator=" + operator + " build=" + buildVersion;
    }
}

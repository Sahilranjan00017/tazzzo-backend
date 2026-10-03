package com.tazzzo.catalog.migration;

import java.util.List;

/** Who is migrating what, where. Never contains a connection string or credentials. */
public record MigrationTarget(String environment, String database, List<String> hosts, String operator,
                              String buildVersion) {

    public String describe() {
        return "environment=" + (environment == null || environment.isBlank() ? "UNSPECIFIED" : environment)
                + " database=" + database + " hosts=" + hosts + " operator=" + operator + " build=" + buildVersion;
    }
}

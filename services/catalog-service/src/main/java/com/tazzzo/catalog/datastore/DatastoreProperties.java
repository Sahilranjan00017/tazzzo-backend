package com.tazzzo.catalog.datastore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prefix {@code tazzzo.datastore}. {@code privilege-verification}: {@code AUTO} (default) enforces the datastore
 * contract and privilege profile for {@code staging}/{@code production} (and for an unidentified environment that
 * points at a non-loopback server); {@code ENFORCE} enforces it everywhere. There is deliberately NO setting that
 * switches enforcement off for staging or production.
 */
@ConfigurationProperties(prefix = "tazzzo.datastore")
public class DatastoreProperties {

    public enum Verification { AUTO, ENFORCE }

    private Verification privilegeVerification = Verification.AUTO;

    public Verification getPrivilegeVerification() { return privilegeVerification; }
    public void setPrivilegeVerification(Verification privilegeVerification) { this.privilegeVerification = privilegeVerification; }
}

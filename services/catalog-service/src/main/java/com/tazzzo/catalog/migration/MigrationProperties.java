package com.tazzzo.catalog.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of database evolution (R5). Prefix {@code tazzzo.migration}. None of these values is a secret.
 * The mode defaults to {@link MigrationMode#VERIFY}: a normally started application never mutates the schema.
 */
@ConfigurationProperties(prefix = "tazzzo.migration")
public class MigrationProperties {

    public enum VerifyFailure { FAIL, WARN }

    private MigrationMode mode = MigrationMode.VERIFY;
    /** local | test | dev | staging | production. Required for any mutation. */
    private String environment = "";
    /** Two-key confirmation for APPLY outside local/test/dev: the exact database name. */
    private String confirmDatabase = "";
    /** Two-key confirmation: the exact environment. */
    private String confirmEnvironment = "";
    /** Operator or deployment job identity recorded in the history; falls back to the OS user. */
    private String operator = "";
    /** Build/application version recorded in the history. */
    private String buildVersion = "unknown";
    private long lockLeaseSeconds = 300;
    private long lockWaitSeconds = 0;
    /** In DRY_RUN/APPLY job modes: exit the process with the run's exit code when finished. */
    private boolean exitAfterRun = false;
    /** What VERIFY does when the database is not at the required migration state. */
    private VerifyFailure verifyFailure = VerifyFailure.FAIL;
    /** Ids of DATA migrations the operator explicitly approves for this run. */
    private List<String> approvedDataMigrations = new ArrayList<>();
    /** Ids of registered-but-disabled migrations (for example drop candidates) enabled for this run. */
    private List<String> enabledMigrations = new ArrayList<>();

    public MigrationMode getMode() { return mode; }
    public void setMode(MigrationMode mode) { this.mode = mode; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getConfirmDatabase() { return confirmDatabase; }
    public void setConfirmDatabase(String confirmDatabase) { this.confirmDatabase = confirmDatabase; }
    public String getConfirmEnvironment() { return confirmEnvironment; }
    public void setConfirmEnvironment(String confirmEnvironment) { this.confirmEnvironment = confirmEnvironment; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getBuildVersion() { return buildVersion; }
    public void setBuildVersion(String buildVersion) { this.buildVersion = buildVersion; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long lockLeaseSeconds) { this.lockLeaseSeconds = lockLeaseSeconds; }
    public long getLockWaitSeconds() { return lockWaitSeconds; }
    public void setLockWaitSeconds(long lockWaitSeconds) { this.lockWaitSeconds = lockWaitSeconds; }
    public boolean isExitAfterRun() { return exitAfterRun; }
    public void setExitAfterRun(boolean exitAfterRun) { this.exitAfterRun = exitAfterRun; }
    public VerifyFailure getVerifyFailure() { return verifyFailure; }
    public void setVerifyFailure(VerifyFailure verifyFailure) { this.verifyFailure = verifyFailure; }
    public List<String> getApprovedDataMigrations() { return approvedDataMigrations; }
    public void setApprovedDataMigrations(List<String> approvedDataMigrations) { this.approvedDataMigrations = approvedDataMigrations; }
    public List<String> getEnabledMigrations() { return enabledMigrations; }
    public void setEnabledMigrations(List<String> enabledMigrations) { this.enabledMigrations = enabledMigrations; }
}

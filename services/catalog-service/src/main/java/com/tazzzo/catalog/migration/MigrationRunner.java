package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the registered migrations in id order with the guarantees R5 requires: explicit target, single runner,
 * recorded history, stop-on-first-problem, retry-safe, and a read-only dry run.
 *
 * <ul>
 *   <li>{@link #dryRun}: strictly read-only (no lock, no history collection, no mutation).</li>
 *   <li>{@link #apply}: guard the target, take the lock, then per migration: skip if recorded applied (verifying the
 *       checksum), preflight, apply, post-validate, record. BLOCKED / FAILED / checksum mismatch / lost lock stop the
 *       run; later migrations are reported NOT_RUN. The lock is always released.</li>
 *   <li>{@link #verify}: read-only check used by application startup.</li>
 * </ul>
 */
public final class MigrationRunner {

    /** Which migrations take part. Empty {@code kinds} means all kinds. */
    public record Selection(Set<MigrationKind> kinds, Set<String> enabledIds, Set<String> approvedIds) {
        public Selection {
            kinds = Set.copyOf(kinds);
            enabledIds = Set.copyOf(enabledIds);
            approvedIds = Set.copyOf(approvedIds);
        }

        public static Selection all() {
            return new Selection(Set.of(), Set.of(), Set.of());
        }

        public static Selection schemaOnly() {
            return new Selection(EnumSet.of(MigrationKind.SCHEMA), Set.of(), Set.of());
        }

        public Selection withEnabled(Set<String> ids) {
            return new Selection(kinds, ids, approvedIds);
        }

        public Selection withApproved(Set<String> ids) {
            return new Selection(kinds, enabledIds, ids);
        }

        boolean includes(Migration m) {
            return (m.enabledByDefault() || enabledIds.contains(m.id())) && (kinds.isEmpty() || kinds.contains(m.kind()));
        }
    }

    public record ApplyOptions(MigrationMode mode, String confirmDatabase, String confirmEnvironment,
                               Duration lease, Duration lockWait) {
        public static ApplyOptions forTests() {
            return new ApplyOptions(MigrationMode.APPLY_ON_STARTUP, null, null, Duration.ofMinutes(5), Duration.ofSeconds(30));
        }
    }

    public enum StepStatus { APPLIED_NOW, ADOPTED, ALREADY_APPLIED, WOULD_APPLY, WOULD_ADOPT, BLOCKED, FAILED,
        PENDING_APPROVAL, CHECKSUM_MISMATCH, NOT_RUN,
        /** The migration was in flight when the lock was lost: possibly partly applied, left APPLYING, re-run to resume. */
        INTERRUPTED }

    public enum Outcome { OK, BLOCKED, FAILED, LOCK_HELD, LOCK_LOST, CHECKSUM_MISMATCH, TARGET_REFUSED, INVALID_SELECTION }

    public record Step(String id, StepStatus status, boolean wouldMutate, List<String> operations,
                       List<String> blockers, List<String> notes, String message) { }

    public record RunReport(Outcome outcome, boolean dryRun, String target, List<Step> steps, String message) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }

        public int exitCode() {
            return switch (outcome) {
                case OK -> 0;
                case BLOCKED -> 2;
                case FAILED -> 3;
                case LOCK_HELD -> 4;
                case LOCK_LOST -> 5;
                case CHECKSUM_MISMATCH -> 6;
                case TARGET_REFUSED -> 7;
                case INVALID_SELECTION -> 8;
            };
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(dryRun ? "DRY-RUN " : "APPLY ").append(outcome).append(" target[").append(target).append(']');
            if (message != null) sb.append(" — ").append(message);
            for (Step s : steps) {
                sb.append("\n  ").append(s.id()).append(": ").append(s.status());
                if (s.wouldMutate()) sb.append(" (would mutate)");
                if (!s.operations().isEmpty()) sb.append("\n      ops: ").append(s.operations());
                if (!s.blockers().isEmpty()) sb.append("\n      BLOCKED: ").append(s.blockers());
                if (!s.notes().isEmpty()) sb.append("\n      notes: ").append(s.notes());
                if (s.message() != null) sb.append("\n      ").append(s.message());
            }
            return sb.toString();
        }
    }

    public record VerifyReport(boolean ok, List<String> problems, List<String> pendingApproval) {
        public String render() {
            return (ok ? "VERIFY OK" : "VERIFY FAILED: " + problems) + (pendingApproval.isEmpty() ? ""
                    : " (data migrations awaiting approval, not required: " + pendingApproval + ")");
        }
    }

    private final MongoDatabase db;
    private final List<Migration> registry;
    private final MigrationHistory history;
    private final MigrationLock lock;
    private final Clock clock;

    public MigrationRunner(MongoDatabase db, List<Migration> registry, MigrationHistory history, MigrationLock lock, Clock clock) {
        this.db = db;
        this.registry = registry.stream().sorted(java.util.Comparator.comparing(Migration::id)).toList();
        this.history = history;
        this.lock = lock;
        this.clock = clock;
    }

    /** A typo in enabled/approved ids must never be silently ignored (it could silently skip an intended approval). */
    private String selectionProblem(Selection sel) {
        Set<String> known = new java.util.HashSet<>();
        registry.forEach(m -> known.add(m.id()));
        List<String> unknown = new ArrayList<>();
        sel.enabledIds().stream().filter(i -> !known.contains(i)).sorted().forEach(i -> unknown.add("enabled:" + i));
        sel.approvedIds().stream().filter(i -> !known.contains(i)).sorted().forEach(i -> unknown.add("approved:" + i));
        return unknown.isEmpty() ? null : "unknown migration id(s) in the selection " + unknown + "; known ids: "
                + registry.stream().map(Migration::id).toList() + ". Nothing was run.";
    }

    private List<Migration> selected(Selection sel) {
        return registry.stream().filter(sel::includes).toList();
    }

    private static boolean checksumMatches(Document h, Migration m) {
        return m.checksum().equals(h.getString("checksum"));
    }

    // ---- dry run -----------------------------------------------------------------------------

    public RunReport dryRun(MigrationTarget target, Selection sel) {
        String bad = selectionProblem(sel);
        if (bad != null) return new RunReport(Outcome.INVALID_SELECTION, true, target.describe(), List.of(), bad);
        List<Step> steps = new ArrayList<>();
        Outcome outcome = Outcome.OK;
        for (Migration m : selected(sel)) {
            Optional<Document> h = history.find(m.id());
            if (h.isPresent() && "APPLIED".equals(h.get().getString("status"))) {
                if (!checksumMatches(h.get(), m)) {
                    steps.add(new Step(m.id(), StepStatus.CHECKSUM_MISMATCH, false, List.of(), List.of(), List.of(),
                            "applied definition differs from the current one (recorded " + h.get().getString("checksum") + ")"));
                    outcome = Outcome.CHECKSUM_MISMATCH;
                } else {
                    steps.add(new Step(m.id(), StepStatus.ALREADY_APPLIED, false, List.of(), List.of(), List.of(), null));
                }
                continue;
            }
            Preflight pf = m.preflight(db);
            switch (pf.status()) {
                case BLOCKED -> {
                    steps.add(new Step(m.id(), StepStatus.BLOCKED, false, List.of(), pf.blockers(), pf.notes(), null));
                    if (outcome == Outcome.OK) outcome = Outcome.BLOCKED;
                }
                case ALREADY_SATISFIED -> steps.add(new Step(m.id(), StepStatus.WOULD_ADOPT, false, List.of(), List.of(), pf.notes(),
                        "target state already holds; a history record would be written, no data change"));
                case READY -> {
                    boolean needsApproval = m.requiresApproval() && !sel.approvedIds().contains(m.id());
                    steps.add(new Step(m.id(), needsApproval ? StepStatus.PENDING_APPROVAL : StepStatus.WOULD_APPLY, true,
                            pf.operations(), List.of(), pf.notes(), needsApproval ? "requires explicit approval" : null));
                }
            }
        }
        return new RunReport(outcome, true, target.describe(), steps, null);
    }

    // ---- apply -------------------------------------------------------------------------------

    private static final class Lease implements AutoCloseable {
        private final MigrationLock lock;
        private final MigrationLock.Held held;
        private final Duration lease;
        private final ScheduledExecutorService ses;
        final AtomicBoolean lost = new AtomicBoolean(false);

        Lease(MigrationLock lock, MigrationLock.Held held, Duration lease) {
            this.lock = lock;
            this.held = held;
            this.lease = lease;
            this.ses = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "migration-lock-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(50L, lease.toMillis() / 3);
            ses.scheduleAtFixedRate(() -> {
                try {
                    if (!lock.renew(held, lease)) lost.set(true);
                } catch (RuntimeException e) {
                    lost.set(true);
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        void requireHeld() {
            if (lost.get()) throw new LockLostException("the migration lock lease was lost; stopping without recording further progress");
        }

        @Override
        public void close() {
            ses.shutdownNow();
            try {
                lock.release(held);
            } catch (RuntimeException ignored) {
                // an unreleased lease simply expires; never mask the migration outcome
            }
        }
    }

    public RunReport apply(MigrationTarget target, Selection sel, ApplyOptions opts) {
        String bad = selectionProblem(sel);
        if (bad != null) return new RunReport(Outcome.INVALID_SELECTION, false, target.describe(), List.of(), bad);
        try {
            TargetGuard.requireMutationAllowed(opts.mode(), target, opts.confirmDatabase(), opts.confirmEnvironment());
        } catch (TargetRefusedException e) {
            return new RunReport(Outcome.TARGET_REFUSED, false, target.describe(), List.of(), e.getMessage());
        }
        String owner = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Optional<MigrationLock.Held> held = acquire(owner, runId, opts);
        if (held.isEmpty()) {
            return new RunReport(Outcome.LOCK_HELD, false, target.describe(), List.of(),
                    "another migration run holds the lock; nothing was changed");
        }
        List<Step> steps = new ArrayList<>();
        List<Migration> todo = selected(sel);
        int index = 0;
        Outcome outcome = Outcome.OK;
        String message = null;
        Migration inFlight = null;
        try (Lease lease = new Lease(lock, held.get(), opts.lease())) {
            history.ensureExists();
            MigrationHistory.RunInfo run = new MigrationHistory.RunInfo(runId, held.get().fence(), target);
            for (; index < todo.size(); index++) {
                Migration m = todo.get(index);
                lease.requireHeld();
                Optional<Document> h = history.find(m.id());
                if (h.isPresent() && "APPLIED".equals(h.get().getString("status"))) {
                    if (!checksumMatches(h.get(), m)) {
                        steps.add(new Step(m.id(), StepStatus.CHECKSUM_MISMATCH, false, List.of(), List.of(), List.of(),
                                "an already-applied migration was edited; add a new migration instead"));
                        outcome = Outcome.CHECKSUM_MISMATCH;
                        index++;
                        break;
                    }
                    steps.add(new Step(m.id(), StepStatus.ALREADY_APPLIED, false, List.of(), List.of(), List.of(), null));
                    continue;
                }
                Preflight pf = m.preflight(db);
                if (pf.status() == Preflight.Status.BLOCKED) {
                    history.markBlocked(m, run, now(), pf.blockers());
                    steps.add(new Step(m.id(), StepStatus.BLOCKED, false, List.of(), pf.blockers(), pf.notes(), null));
                    outcome = Outcome.BLOCKED;
                    index++;
                    break;
                }
                long started = System.nanoTime();
                if (pf.status() == Preflight.Status.ALREADY_SATISFIED) {
                    lease.requireHeld();
                    history.markApplied(m, run, now(), "adopted: target state already holds. " + String.join(" ", pf.notes()),
                            null, 0, true);
                    steps.add(new Step(m.id(), StepStatus.ADOPTED, false, List.of(), List.of(), pf.notes(), null));
                    continue;
                }
                if (m.requiresApproval() && !sel.approvedIds().contains(m.id())) {
                    steps.add(new Step(m.id(), StepStatus.PENDING_APPROVAL, true, pf.operations(), List.of(), pf.notes(),
                            "data migration requires explicit approval; not run"));
                    continue;
                }
                history.markApplying(m, run, now());
                inFlight = m;
                try {
                    ApplyResult result = m.apply(db);
                    List<String> problems = m.validate(db);
                    lease.requireHeld();
                    if (!problems.isEmpty()) {
                        history.markFailed(m, run, now(), "post-validation failed: " + problems);
                        steps.add(new Step(m.id(), StepStatus.FAILED, false, pf.operations(), List.of(), pf.notes(),
                                "post-validation failed: " + problems));
                        inFlight = null;
                        outcome = Outcome.FAILED;
                        index++;
                        break;
                    }
                    history.markApplied(m, run, now(), result.note(), result.rollbackInfo(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), false);
                    steps.add(new Step(m.id(), StepStatus.APPLIED_NOW, true, pf.operations(), List.of(), pf.notes(), result.note()));
                    inFlight = null;
                } catch (LockLostException e) {
                    throw e;
                } catch (RuntimeException e) {
                    String safe = MigrationSanitizer.safeMessage(e);
                    history.markFailed(m, run, now(), safe);
                    steps.add(new Step(m.id(), StepStatus.FAILED, false, pf.operations(), List.of(), pf.notes(), safe));
                    inFlight = null;
                    outcome = Outcome.FAILED;
                    index++;
                    break;
                }
            }
        } catch (LockLostException e) {
            outcome = Outcome.LOCK_LOST;
            message = e.getMessage();
            if (inFlight != null) {
                steps.add(new Step(inFlight.id(), StepStatus.INTERRUPTED, false, List.of(), List.of(), List.of(),
                        "the lock was lost while this migration was being applied; it may be partly applied and is left APPLYING — re-run to resume"));
            }
        }
        for (Migration m : todo) {
            final String id = m.id();
            if (steps.stream().noneMatch(st -> st.id().equals(id))) {
                steps.add(new Step(id, StepStatus.NOT_RUN, false, List.of(), List.of(), List.of(),
                        "not run: an earlier migration stopped the run"));
            }
        }
        return new RunReport(outcome, false, target.describe(), steps, message);
    }

    private Optional<MigrationLock.Held> acquire(String owner, String runId, ApplyOptions opts) {
        long deadline = System.nanoTime() + opts.lockWait().toNanos();
        while (true) {
            Optional<MigrationLock.Held> h = lock.tryAcquire(owner, runId, opts.lease());
            if (h.isPresent() || System.nanoTime() >= deadline) return h;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    // ---- verify (runtime, read-only) ---------------------------------------------------------

    public VerifyReport verify(Selection sel) {
        List<String> problems = new ArrayList<>();
        List<String> pendingApproval = new ArrayList<>();
        if (!history.exists()) {
            problems.add("database is not migrated: no migration history exists (run the migration job: dry-run, then apply)");
        }
        for (Migration m : selected(sel)) {
            Optional<Document> h = history.find(m.id());
            if (m.kind() == MigrationKind.DATA) {
                // data migrations are never required for the application to serve; surface the state only
                if (h.isEmpty() || !"APPLIED".equals(h.get().getString("status"))) pendingApproval.add(m.id());
                else if (!checksumMatches(h.get(), m)) problems.add(m.id() + ": applied definition differs from the current one");
                continue;
            }
            if (h.isEmpty()) {
                if (history.exists()) problems.add(m.id() + ": not applied");
                continue;
            }
            String status = h.get().getString("status");
            if (!"APPLIED".equals(status)) {
                problems.add(m.id() + ": status " + status + (h.get().getString("error") == null ? "" : " (" + h.get().getString("error") + ")"));
            } else if (!checksumMatches(h.get(), m)) {
                problems.add(m.id() + ": applied definition differs from the current one (checksum mismatch)");
            }
        }
        return new VerifyReport(problems.isEmpty(), problems, pendingApproval);
    }
}

package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.BsonDocument;
import org.bson.Document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Shared fixtures for the Membership integration tests (real Mongo via {@link AbstractMongoIT}). */
abstract class AbstractMembershipIT extends AbstractMongoIT {

    static final String PLAN_ID = "TAZZZO_PLUS_MONTHLY";
    /** 2027-01-31 10:00 IST: one month later clamps to 28 Feb 2027 10:00 IST (= 2027-02-28T04:30Z). */
    static final Instant T0 = Instant.parse("2027-01-31T04:30:00Z");
    static final Instant T0_UNTIL = Instant.parse("2027-02-28T04:30:00Z");

    static final MembershipPlan PLAN = new MembershipPlan(PLAN_ID, 1, Money.ofInrPaise(9900), 1,
            Instant.parse("2026-01-01T00:00:00Z"), null);

    MutableClock clock;
    SimpleMeterRegistry registry;

    void resetFixtures() {
        clock = new MutableClock(T0);
        registry = new SimpleMeterRegistry();
    }

    static CustomerId newCustomer() {
        return CustomerId.generate();
    }

    static String newRef() {
        return "REF-" + UUID.randomUUID();
    }

    MembershipPlanSource planSource(MembershipPlan... plans) {
        return new ConfigBackedMembershipPlanSource(List.of(plans));
    }

    MembershipService service() {
        return service(new Tx(client), new MembershipRepository(db), planSource(PLAN));
    }

    MembershipService service(Tx tx, MembershipRepository repository, MembershipPlanSource plans) {
        return new MembershipService(repository, plans, new MembershipObservability(registry), clock, tx);
    }

    List<Document> rows(CustomerId customer) {
        return db.getCollection(MembershipRepository.COLLECTION).find(Filters.eq("customerId", customer.value()))
                .into(new java.util.ArrayList<>());
    }

    Document raw(MembershipId id) {
        return db.getCollection(MembershipRepository.COLLECTION).find(Filters.eq("_id", id.value())).first();
    }

    double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    double totalMeters() {
        return registry.getMeters().size();
    }

    static MembershipWriteFailureProbe expect(Runnable r) {
        return new MembershipWriteFailureProbe(r);
    }

    /** Runs a call expected to throw {@link MembershipFailure} and exposes its reason. */
    static final class MembershipWriteFailureProbe {
        private final Runnable call;

        MembershipWriteFailureProbe(Runnable call) {
            this.call = call;
        }

        MembershipFailure.Reason reason() {
            try {
                call.run();
            } catch (MembershipFailure e) {
                return e.reason();
            }
            throw new AssertionError("expected a MembershipFailure but the call succeeded");
        }
    }

    // ---------- test doubles ----------

    /** A real repository whose reads can be made to "miss" durable rows, and whose writes can be made to fail,
     *  deterministically (no sleeps) -- to drive the duplicate-key recovery paths. A negative counter means
     *  "always". */
    static class ScriptedRepository extends MembershipRepository {
        final AtomicInteger hideNonSessionRefLookups = new AtomicInteger();
        final AtomicInteger hideSessionRefLookups = new AtomicInteger();
        final AtomicInteger hideSessionOpenLookups = new AtomicInteger();
        final AtomicInteger nonSessionRefCalls = new AtomicInteger();
        volatile boolean failNonSessionReadsAfterFirst;
        volatile boolean failInsert;
        volatile boolean expireCasMisses;

        ScriptedRepository(com.mongodb.client.MongoDatabase db) {
            super(db);
        }

        private static boolean consume(AtomicInteger hide) {
            int v = hide.get();
            if (v < 0) {
                return true;
            }
            if (v > 0) {
                hide.decrementAndGet();
                return true;
            }
            return false;
        }

        @Override
        public Optional<Membership> findByGrantReference(MembershipGrantReference reference) {
            int call = nonSessionRefCalls.incrementAndGet();
            if (failNonSessionReadsAfterFirst && call >= 2) {
                throw new MongoException("simulated recovery-read failure");
            }
            if (consume(hideNonSessionRefLookups)) {
                return Optional.empty();
            }
            return super.findByGrantReference(reference);
        }

        @Override
        public Optional<Membership> findByGrantReference(ClientSession session, MembershipGrantReference reference) {
            return consume(hideSessionRefLookups) ? Optional.empty() : super.findByGrantReference(session, reference);
        }

        @Override
        public Optional<Membership> findOpenByCustomer(ClientSession session, CustomerId customerId) {
            return consume(hideSessionOpenLookups) ? Optional.empty() : super.findOpenByCustomer(session, customerId);
        }

        @Override
        public void insert(ClientSession session, Membership membership) {
            if (failInsert) {
                throw new MongoException("simulated insert failure");
            }
            super.insert(session, membership);
        }

        @Override
        public boolean expireIfDue(ClientSession session, MembershipId id, long expectedVersion, Instant now) {
            if (expireCasMisses) {
                return false;
            }
            return super.expireIfDue(session, id, expectedVersion, now);
        }
    }

    /** A real {@link Tx} that counts whole-transaction attempts. */
    static final class CountingTx extends Tx {
        final AtomicInteger calls = new AtomicInteger();
        private final Tx real;

        CountingTx(com.mongodb.client.MongoClient client) {
            super(client);
            this.real = new Tx(client);
        }

        @Override
        public <T> T call(Function<ClientSession, T> body) {
            calls.incrementAndGet();
            return real.call(body);
        }
    }

    /** A Tx that never touches Mongo: every call "loses a duplicate-key race" with the given server text. */
    static final class DuplicateKeyTx extends Tx {
        final AtomicInteger calls = new AtomicInteger();
        private final String message;

        DuplicateKeyTx(com.mongodb.client.MongoClient client, String message) {
            super(client);
            this.message = message;
        }

        @Override
        public <T> T call(Function<ClientSession, T> body) {
            calls.incrementAndGet();
            throw new MongoWriteException(new WriteError(11000, message, new BsonDocument()), new ServerAddress());
        }
    }

    /** A Tx that always fails as a raw, non-transient datastore outage. */
    static final class OutageTx extends Tx {
        OutageTx(com.mongodb.client.MongoClient client) {
            super(client);
        }

        @Override
        public <T> T call(Function<ClientSession, T> body) {
            throw new MongoException("simulated datastore outage");
        }
    }
}

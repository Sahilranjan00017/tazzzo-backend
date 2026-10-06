package com.tazzzo.customer.address;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.session.CustomerIdentityAuthorityImpl;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import org.bson.BsonDocument;
import org.bson.Document;

/**
 * The same-key race, made deterministic: a competing create with the same key COMMITS while our transaction is between
 * its key lookup and its key insert. Under snapshot isolation our insert raises a transient write conflict, the driver
 * retries the whole callback, and the retry takes the replay path; the duplicate-key outcome (should a driver ever
 * surface one instead) is fault-injected to prove the defensive resolution path. Exactly one address, counted once.
 */
@SpringBootTest(classes = {CatalogApplication.class, AddressServiceIT.TestBeans.class})
class AddressIdempotencyRaceIT extends AbstractMongoIT {

    @Autowired AddressService service;
    @Autowired AddressRepository addressRepo;
    @Autowired CustomerAddressStateRepository stateRepo;
    @Autowired AddressLimitProperties limits;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired AddressObservability observability;
    @Autowired CustomerIdentityAuthority authority;

    static AddressService.CreateCommand cmd(String line1) {
        return new AddressService.CreateCommand("HOME", "Asha Rao", "+919876500001", line1, null, null,
                "Bengaluru", "Karnataka", "560047", null, null);
    }

    AddressService withIdempotency(AddressIdempotencyRepository repo) {
        return new AddressService(addressRepo, stateRepo, limits, clock, observability, new FixedObjectProvider<>(authority), tx, repo);
    }

    long addresses(CustomerId c) {
        return db.getCollection("customer_addresses").countDocuments(new Document("customerId", c.value()));
    }

    long counted(CustomerId c) {
        Document s = db.getCollection("customer_address_state").find(new Document("_id", c.value())).first();
        return s == null ? 0 : s.get("addressCount", Number.class).longValue();
    }

    @Test
    void a_competitor_committing_mid_transaction_makes_the_retry_replay_its_address() {
        CustomerId c = new CustomerId("CUS_idemrace01");
        AtomicInteger inserts = new AtomicInteger();
        AtomicReference<String> winner = new AtomicReference<>();
        AtomicInteger finds = new AtomicInteger();
        AddressService racing = withIdempotency(new AddressIdempotencyRepository(db) {
            @Override
            Document find(ClientSession session, String id) {
                Document seen = super.find(session, id);              // our snapshot: no row yet
                if (finds.getAndIncrement() == 0) {
                    winner.set(service.create(c, cmd("12 MG Road"), "race-key-0001").addressId()); // commits now
                }
                return seen;
            }

            @Override
            void insert(ClientSession session, String id, String customerId, String requestHash, String addressId, Instant now) {
                inserts.incrementAndGet();
                super.insert(session, id, customerId, requestHash, addressId, now);
            }
        });
        AddressService.AddressView mine = racing.create(c, cmd("12 MG Road"), "race-key-0001");
        assertThat(mine.addressId()).isEqualTo(winner.get());
        assertThat(finds.get()).as("the conflicting attempt was retried").isGreaterThanOrEqualTo(2);
        assertThat(inserts.get()).as("our attempts never inserted a key row: the retry replayed").isZero();
        assertThat(addresses(c)).isEqualTo(1);
        assertThat(counted(c)).isEqualTo(1);
    }

    @Test
    void a_competitor_with_another_body_under_the_same_key_makes_the_loser_conflict() {
        CustomerId c = new CustomerId("CUS_idemrace02");
        AtomicInteger inserts = new AtomicInteger();
        AddressService racing = withIdempotency(new AddressIdempotencyRepository(db) {
            @Override
            Document find(ClientSession session, String id) {
                Document seen = super.find(session, id);
                if (inserts.getAndIncrement() == 0) {
                    service.create(c, cmd("1 Other Road"), "race-key-0002");
                }
                return seen;
            }
        });
        assertThatThrownBy(() -> racing.create(c, cmd("12 MG Road"), "race-key-0002"))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason()).isEqualTo(AddressFailure.Reason.IDEMPOTENCY_CONFLICT));
        assertThat(addresses(c)).isEqualTo(1);
        assertThat(counted(c)).isEqualTo(1);
    }

    @Test
    void a_duplicate_key_outcome_resolves_to_the_winners_address() {
        CustomerId c = new CustomerId("CUS_idemrace03");
        AtomicReference<String> winner = new AtomicReference<>();
        AtomicInteger finds = new AtomicInteger();
        AddressService racing = withIdempotency(new AddressIdempotencyRepository(db) {
            @Override
            Document find(ClientSession session, String id) {
                if (finds.getAndIncrement() == 1) {
                    // between the aborted attempt and the resolution: the winner is now durable
                    winner.set(service.create(c, cmd("12 MG Road"), "race-key-0003").addressId());
                }
                return super.find(session, id);
            }

            @Override
            void insert(ClientSession session, String id, String customerId, String requestHash, String addressId, Instant now) {
                throw new MongoWriteException(new WriteError(11000, "E11000 duplicate key", new BsonDocument()),
                        new ServerAddress());
            }
        });
        AddressService.AddressView mine = racing.create(c, cmd("12 MG Road"), "race-key-0003");
        assertThat(mine.addressId()).isEqualTo(winner.get());
        assertThat(addresses(c)).isEqualTo(1);
        assertThat(counted(c)).isEqualTo(1);
    }
}

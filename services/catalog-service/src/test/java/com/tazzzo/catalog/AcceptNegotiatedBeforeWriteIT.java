package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP correctness: an {@code Accept} header the route cannot satisfy is refused BEFORE the handler runs, so a 406 never
 * follows a committed write. Before this, Spring negotiated the representation only when writing the response: the
 * address, cart line, quote, order, support case or account deletion was already committed when the client was told 406.
 * Each test proves the refused request left no trace, the same request with an acceptable {@code Accept} writes exactly
 * once, idempotent retries keep their guarantees, and a refused read is never also counted as a success.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AcceptNegotiatedBeforeWriteIT extends AbstractConsumerIT {

    static final String SKU = "TZP-90000093";
    static final String PIN = "560093";
    static final String LOC = "FL-ACCEPT";
    static final String J = "application/json";
    static final String XML = "application/xml";
    static final AtomicInteger PHONES = new AtomicInteger(9300);

    static String key(String seed) {
        return Base64.getEncoder().encodeToString(seed.getBytes(StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_accept_before_write_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> key("accept-cursor-fixture-key-32byte"));
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> key("accept-access-fixture-key-32byte"));
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> key("accept-refresh-fixture-key-32byt"));
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @Autowired MongoClient client;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired MeterRegistry meters;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void operatorSeedsTheStore() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        Clock clock = Clock.systemUTC();
        Tx tx = new Tx(client);
        PricingService pricing = new PricingService(tx, new WritePath(db), clock);
        eligibleProduct(SKU, "TZV-000001");
        pricing.upsertPrice(new UpsertPriceCommand(SKU, 24900, 29900, Currency.INR, null, null, "seed", null));
        new ProductCardProjectionService(new CatalogCardReader(db), pricing, new MediaService(tx, new WritePath(db), clock), db,
                clock).rebuildOne(SKU);
        new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock).upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-ACCEPT", List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));
        new InventoryService(tx, new WritePath(db), clock).setInventory(new SetInventoryCommand(SKU, LOC, 20, 2, 10, "seed", null));
    }

    // ---------- writes: a refused Accept leaves no trace; the acceptable retry writes exactly once ----------

    @Test
    void address_create_is_refused_before_it_is_saved(CapturedOutput log) throws Exception {
        Customer c = newCustomer();
        Res refused = call("POST", "/v1/customer/addresses", c.token, XML, Map.of(), address(c));
        notAcceptable(refused, "INVALID_REQUEST");
        assertThat(count("customer_addresses", c)).as("no address was written").isZero();
        assertThat(db.getCollection("customer_address_state").countDocuments(new Document("_id", c.id))).isZero();

        Res created = call("POST", "/v1/customer/addresses", c.token, J, Map.of(), address(c));
        assertThat(created.status).as(created.raw).isEqualTo(201);
        assertThat(count("customer_addresses", c)).as("the acceptable request writes exactly once").isEqualTo(1);
        quiet(log);
    }

    @Test
    void address_idempotency_key_is_not_consumed_by_a_refused_request() throws Exception {
        Customer c = newCustomer();
        Map<String, String> idem = Map.of("Idempotency-Key", "idem-" + UUID.randomUUID());
        notAcceptable(call("POST", "/v1/customer/addresses", c.token, XML, idem, address(c)), "INVALID_REQUEST");
        assertThat(db.getCollection("customer_address_idempotency").countDocuments(new Document("customer_id", c.id)))
                .as("no idempotency key was recorded").isZero();

        Res first = call("POST", "/v1/customer/addresses", c.token, J, idem, address(c));
        Res replay = call("POST", "/v1/customer/addresses", c.token, J, idem, address(c));
        assertThat(first.status).as(first.raw).isEqualTo(201);
        assertThat(replay.status / 100).as(replay.raw).isEqualTo(2);
        assertThat(replay.body.get("addressId").asText()).as("the retry replays, never duplicates")
                .isEqualTo(first.body.get("addressId").asText());
        assertThat(count("customer_addresses", c)).isEqualTo(1);
    }

    @Test
    void cart_quote_and_cash_on_delivery_order_are_refused_before_any_side_effect() throws Exception {
        Customer c = newCustomer();
        String addressId = call("POST", "/v1/customer/addresses", c.token, J, Map.of(), address(c)).body.get("addressId").asText();

        // cart: the refused add leaves the cart at version 0
        notAcceptable(call("PUT", "/v1/customer/cart/items/" + SKU, c.token, XML, Map.of("If-Match", "\"cart-0\""),
                Map.of("quantity", 2)), "INVALID_REQUEST");
        assertThat(db.getCollection("customer_carts").countDocuments(new Document("_id", c.id))).as("no cart was written").isZero();
        assertThat(call("PUT", "/v1/customer/cart/items/" + SKU, c.token, J, Map.of("If-Match", "\"cart-0\""),
                Map.of("quantity", 2)).status).isEqualTo(200);

        // quote: a refused quote is not stored and does not consume its idempotency key
        Map<String, String> quoteHeaders = Map.of("If-Match", "\"cart-1\"", "Idempotency-Key", "idem-" + UUID.randomUUID());
        notAcceptable(call("POST", "/v1/customer/checkout/quote", c.token, XML, quoteHeaders, Map.of("addressId", addressId)),
                "INVALID_REQUEST");
        assertThat(count("checkout_quotes", c)).as("no quote was written").isZero();
        Res quote = call("POST", "/v1/customer/checkout/quote", c.token, J, quoteHeaders, Map.of("addressId", addressId));
        Res quoteRetry = call("POST", "/v1/customer/checkout/quote", c.token, J, quoteHeaders, Map.of("addressId", addressId));
        assertThat(quote.status).as(quote.raw).isEqualTo(200);
        assertThat(quoteRetry.body.get("quoteId").asText()).as("idempotent quote retry").isEqualTo(quote.body.get("quoteId").asText());
        assertThat(count("checkout_quotes", c)).isEqualTo(1);

        // order: the refused placement moves no stock and creates no order
        long stock = onHand();
        Map<String, Object> place = Map.of("quoteId", quote.body.get("quoteId").asText(), "paymentMethod", "COD");
        notAcceptable(call("POST", "/v1/customer/orders", c.token, XML, Map.of(), place), "INVALID_REQUEST");
        assertThat(count("orders", c)).as("no order was placed").isZero();
        assertThat(onHand()).as("no stock moved").isEqualTo(stock);

        Res order = call("POST", "/v1/customer/orders", c.token, J, Map.of(), place);
        assertThat(order.status).as(order.raw).isEqualTo(200);
        assertThat(order.body.get("status").asText()).isEqualTo("CONFIRMED");
        Res again = call("POST", "/v1/customer/orders", c.token, J, Map.of(), place);
        assertThat(again.status).as("a retry never places a second order: " + again.raw).isNotEqualTo(500);
        assertThat(count("orders", c)).as("exactly one order").isEqualTo(1);
        assertThat(onHand()).as("stock moved exactly once").isEqualTo(stock - 2);
    }

    @Test
    void support_case_and_account_deletion_are_refused_before_they_happen() throws Exception {
        Customer c = newCustomer();
        Map<String, Object> open = Map.of("category", "DELIVERY", "subject", "late", "message", "where is it");
        notAcceptable(call("POST", "/v1/customer/support/cases", c.token, XML, Map.of(), open), "INVALID_REQUEST");
        assertThat(count("support_cases", c)).as("no support case was opened").isZero();
        assertThat(call("POST", "/v1/customer/support/cases", c.token, J, Map.of(), open).status).isEqualTo(201);
        assertThat(count("support_cases", c)).isEqualTo(1);

        notAcceptable(call("POST", "/v1/customer/account/deletion", c.token, XML, Map.of(), Map.of("confirm", "DELETE")),
                "INVALID_REQUEST");
        assertThat(call("GET", "/v1/customer/profile", c.token, J, Map.of(), null).status)
                .as("the account still exists after a refused deletion").isEqualTo(200);
        assertThat(count("support_cases", c)).as("nothing was erased").isEqualTo(1);
    }

    @Test
    void profile_update_is_refused_before_it_is_applied() throws Exception {
        Customer c = newCustomer();
        Res read = call("GET", "/v1/customer/profile", c.token, J, Map.of(), null);
        String etag = read.etag;
        notAcceptable(call("PATCH", "/v1/customer/profile", c.token, XML, Map.of("If-Match", etag),
                Map.of("displayName", "Refused Name")), "INVALID_REQUEST");
        Res after = call("GET", "/v1/customer/profile", c.token, J, Map.of(), null);
        assertThat(after.etag).as("the profile version did not move").isEqualTo(etag);
        assertThat(after.raw).doesNotContain("Refused Name");
    }

    // ---------- reads, compatibility, authentication ----------

    @Test
    void a_refused_read_is_counted_once_as_a_failure_and_never_as_a_success() throws Exception {
        Customer c = newCustomer();
        double successBefore = counter("customer_cart_read_success");
        notAcceptable(call("GET", "/v1/customer/cart", c.token, XML, Map.of(), null), "INVALID_REQUEST");
        assertThat(counter("customer_cart_read_success")).as("not counted as a success").isEqualTo(successBefore);
        assertThat(call("GET", "/v1/customer/cart", c.token, J, Map.of(), null).status).isEqualTo(200);
        assertThat(counter("customer_cart_read_success")).isEqualTo(successBefore + 1);
    }

    /** Every Accept a JSON client may legitimately send keeps working exactly as before. */
    @Test
    void acceptable_accept_headers_are_unchanged() throws Exception {
        Customer c = newCustomer();
        for (String accept : new String[]{null, "*/*", J, "application/*", "application/json;charset=UTF-8",
                "application/problem+json", "text/html, application/json;q=0.9", "application/xml, */*;q=0.1"}) {
            Res r = call("GET", "/v1/customer/addresses", c.token, accept, Map.of(), null);
            assertThat(r.status).as("Accept " + accept + " -> " + r.raw).isEqualTo(200);
        }
        for (String accept : new String[]{XML, "text/html", "text/plain", "image/png"}) {
            notAcceptable(call("GET", "/v1/customer/addresses", c.token, accept, Map.of(), null), "INVALID_REQUEST");
        }
    }

    /** Authentication still runs first: no token is a 401 whatever the Accept, and nothing is written. */
    @Test
    void authentication_stays_authoritative() throws Exception {
        long before = db.getCollection("customer_addresses").countDocuments();
        assertThat(call("POST", "/v1/customer/addresses", null, XML, Map.of(), Map.of("label", "HOME")).status).isEqualTo(401);
        assertThat(call("POST", "/v1/customer/addresses", "not-a-token", XML, Map.of(), Map.of("label", "HOME")).status)
                .isEqualTo(401);
        assertThat(call("POST", "/api/v1/admin/prices", null, XML, Map.of(), Map.of()).status).isEqualTo(401);
        assertThat(db.getCollection("customer_addresses").countDocuments()).isEqualTo(before);
    }

    // ---------- helpers ----------

    record Customer(String id, String token, String phone) { }

    record Res(int status, String contentType, String etag, JsonNode body, String raw) { }

    Customer newCustomer() throws Exception {
        String phone = "+9198765" + PHONES.incrementAndGet() + "1";
        String grantId = "GRANT_" + UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        Res s = call("POST", "/v1/auth/session", null, J, Map.of(), Map.of("grantId", grantId));
        assertThat(s.status).as(s.raw).isEqualTo(200);
        return new Customer(s.body.get("customerId").asText(), s.body.get("accessToken").asText(), phone);
    }

    static Map<String, Object> address(Customer c) {
        return Map.of("label", "HOME", "recipientName", "Asha Rao", "recipientPhone", c.phone, "addressLine1",
                "4 Residency Road", "city", "Bengaluru", "state", "Karnataka", "postalCode", PIN);
    }

    Res call(String method, String path, String bearer, String accept, Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) b.header("Content-Type", J);
        if (accept != null) b.header("Accept", accept);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed;
        try {
            parsed = r.body().isEmpty() ? json.nullNode() : json.readTree(r.body());
        } catch (Exception e) {
            parsed = json.nullNode();
        }
        return new Res(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                r.headers().firstValue("ETag").orElse(null), parsed, r.body());
    }

    /** A 406 in the route's own documented JSON error shape, with no representation of a write attached. */
    static void notAcceptable(Res r, String code) {
        assertThat(r.status).as(r.raw).isEqualTo(406);
        assertThat(r.contentType).startsWith(J);
        assertThat(r.body.path("code").asText()).as(r.raw).isEqualTo(code);
        assertThat(r.body.path("requestId").asText()).startsWith("req_");
        assertThat(r.etag).as("no ETag of a representation that was never produced").isNull();
    }

    long count(String collection, Customer c) {
        return db.getCollection(collection).countDocuments(new Document("customerId", c.id));
    }

    long onHand() {
        return db.getCollection("inventory").find(new Document("sku_id", SKU).append("fulfillment_location_id", LOC)).first()
                .get("on_hand", Number.class).longValue();
    }

    double counter(String name) {
        var c = meters.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private static void quiet(CapturedOutput log) {
        assertThat(log.getOut()).doesNotContain("_internal type=").doesNotContain("\tat org.springframework")
                .doesNotContain("Failure in @ExceptionHandler");
    }
}

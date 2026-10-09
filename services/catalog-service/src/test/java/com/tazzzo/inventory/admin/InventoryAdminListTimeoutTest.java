package com.tazzzo.inventory.admin;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.inventory.InventoryListTimeoutException;
import com.tazzzo.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The 503 {@code LIST_TIMEOUT} path of {@code GET /api/v1/admin/inventory}, deterministically and without Docker: the
 * Mongo query chain is a stub whose terminal call throws the driver's {@link MongoExecutionTimeoutException} (what the
 * server returns when {@code maxTimeMS} expires). Production keeps its 2 s bound; the test also asserts that the bound is
 * really sent with the query, so a regression that drops {@code maxTime} would fail here.
 */
class InventoryAdminListTimeoutTest {

    private final AtomicLong maxTimeSeen = new AtomicLong(-1);
    private final InventoryService service = new InventoryService(
            new Tx(stub(MongoClient.class)), new WritePath(timingOutDatabase()), Clock.systemUTC());

    @Test
    void the_service_maps_a_server_side_timeout_to_the_list_timeout_exception() {
        assertThatThrownBy(() -> service.list(null, null, null, null, 10)).isInstanceOf(InventoryListTimeoutException.class);
        assertThat(maxTimeSeen.get()).as("the query carries the production bound").isEqualTo(2_000L);
    }

    @Test
    void the_admin_api_answers_503_list_timeout_in_the_admin_envelope() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InventoryAdminListController(service))
                .setControllerAdvice(new InventoryAdminExceptionHandler()).build();
        var response = mvc.perform(get("/api/v1/admin/inventory?limit=5")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":\"LIST_TIMEOUT\"").contains("request_id");
    }

    @SuppressWarnings("unchecked")
    private MongoDatabase timingOutDatabase() {
        // db.getCollection(..) -> collection; collection.find(..) -> cursor chain; sort/limit/maxTime keep chaining; into(..) times out
        Object cursor = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{com.mongodb.client.FindIterable.class},
                (self, m, args) -> {
                    switch (m.getName()) {
                        case "maxTime" -> {
                            maxTimeSeen.set(((java.util.concurrent.TimeUnit) args[1]).toMillis((Long) args[0]));
                            return self;
                        }
                        case "sort", "limit" -> {
                            return self;
                        }
                        case "into" -> throw new MongoExecutionTimeoutException(50, "operation exceeded time limit");
                        default -> throw new UnsupportedOperationException(m.getName());
                    }
                });
        Object collection = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{com.mongodb.client.MongoCollection.class},
                (self, m, args) -> {
                    if (m.getName().equals("find")) return cursor;
                    throw new UnsupportedOperationException(m.getName());
                });
        return (MongoDatabase) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MongoDatabase.class},
                (self, m, args) -> {
                    if (m.getName().equals("getCollection")) return collection;
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> null);
    }
}

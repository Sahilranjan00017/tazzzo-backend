package com.tazzzo.external;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.inventory.InventoryReservation;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationItem;
import com.tazzzo.inventory.InventoryReservationRequest;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.PreparedInventoryReservation;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-14A hardening (M2, this review) — deliberately in a DIFFERENT package from
 * {@code com.tazzzo.inventory}, proving {@link PreparedInventoryReservation} is a normal, nameable,
 * usable cross-package type: an external caller (this class stands in for a future
 * {@code customer.order}) declares it as an ordinary local variable (not {@code var}-only), passes
 * it into {@code reserve}, and reads its public accessors — while being structurally unable to
 * construct one directly.
 */
@SpringBootTest(classes = CatalogApplication.class)
class InventoryReservationCrossPackageUsageTest extends AbstractMongoIT {

    @Autowired InventoryReservationService reservations;
    @Autowired MongoClient client;
    @Autowired MongoDatabase mongoDb;
    @Autowired Tx tx;

    private void seed(String sku, String loc, long onHand) {
        mongoDb.getCollection("inventory").insertOne(new Document("sku_id", sku)
                .append("fulfillment_location_id", loc).append("on_hand", onHand).append("reserved", 0L)
                .append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", true).append("source", "seed").append("created_at", new Date())
                .append("updated_at", new Date()));
    }

    @Test void an_external_package_can_prepare_and_reserve_using_the_public_type_by_name() {
        seed("TZP-EXT1", "FL-EXT-1", 10);
        InventoryReservationRequest request = new InventoryReservationRequest("ORD-EXT1", "FL-EXT-1",
                List.of(new InventoryReservationItem("TZP-EXT1", 3)));

        // an ordinary, explicitly-typed local variable -- not `var`, not held opaquely
        PreparedInventoryReservation prepared = reservations.prepare(request);
        assertThat(prepared.orderId()).isEqualTo("ORD-EXT1");
        assertThat(prepared.fulfillmentLocationId()).isEqualTo("FL-EXT-1");
        assertThat(prepared.items()).hasSize(1);
        assertThat(prepared.reservationId()).isNotNull();
        assertThat(prepared.expiresAt()).isAfter(prepared.preparedAt());

        InventoryReservation r = tx.call(session -> reservations.reserve(session, prepared)); // proves it composes as documented
        assertThat(r.reservationId()).isEqualTo(prepared.reservationId().value());
    }

    @Test void the_prepared_type_has_no_public_constructor_and_cannot_be_forged_here() {
        assertThat(PreparedInventoryReservation.class.getModifiers() & Modifier.PUBLIC)
                .as("the type itself must be public and nameable from another package").isNotZero();
        for (var ctor : PreparedInventoryReservation.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(ctor.getModifiers()))
                    .as("no public constructor may exist -- this package cannot construct one directly")
                    .isFalse();
        }
    }
}

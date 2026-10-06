package com.tazzzo.delivery;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.serviceability.ServiceabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Wires {@link DeliverySlotService}. The zone and horizon are configuration, never request input. */
@Configuration
class DeliveryConfig {

    @Bean
    DeliverySlotService deliverySlotService(Tx tx, MongoDatabase db, ServiceabilityService serviceability,
                                            @Value("${tazzzo.delivery.zone:Asia/Kolkata}") String zone,
                                            @Value("${tazzzo.delivery.horizon-days:3}") int horizonDays) {
        Clock clock = Clock.systemUTC();
        return new DeliverySlotService(tx, db, new DomainAudit(db, clock), serviceability, clock, ZoneId.of(zone), horizonDays);
    }
}

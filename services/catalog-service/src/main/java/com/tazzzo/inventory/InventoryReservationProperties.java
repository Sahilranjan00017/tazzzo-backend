package com.tazzzo.inventory;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PR-14A — explicit, bounded reservation settings. Launch default: 10 minutes — long enough to
 * cover a customer completing checkout-to-order plus a prepaid provider round trip, short enough
 * that an abandoned reservation does not lock stock for long.
 */
@ConfigurationProperties(prefix = "tazzzo.inventory-reservation")
public class InventoryReservationProperties {

    private int ttlSeconds = 600;
    private int expiryBatchSize = 100;

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getExpiryBatchSize() {
        return expiryBatchSize;
    }

    public void setExpiryBatchSize(int expiryBatchSize) {
        this.expiryBatchSize = expiryBatchSize;
    }

    @PostConstruct
    void validate() {
        if (ttlSeconds < 60 || ttlSeconds > 3600) {
            throw new IllegalStateException("tazzzo.inventory-reservation.ttl-seconds must be within 60..3600");
        }
        if (expiryBatchSize < 1 || expiryBatchSize > 1000) {
            throw new IllegalStateException(
                    "tazzzo.inventory-reservation.expiry-batch-size must be within 1..1000");
        }
    }
}

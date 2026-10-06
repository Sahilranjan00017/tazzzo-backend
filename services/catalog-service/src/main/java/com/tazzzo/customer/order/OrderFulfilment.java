package com.tazzzo.customer.order;

import java.time.Instant;

/** When staff handed the order to delivery, and when it was delivered. Either may be absent; never invented. */
public record OrderFulfilment(Instant outForDeliveryAt, Instant deliveredAt) {

    public static final OrderFulfilment NONE = new OrderFulfilment(null, null);

    public OrderFulfilment {
        if (deliveredAt != null && (outForDeliveryAt == null || deliveredAt.isBefore(outForDeliveryAt))) {
            throw new IllegalArgumentException("deliveredAt requires an earlier-or-equal outForDeliveryAt");
        }
    }
}

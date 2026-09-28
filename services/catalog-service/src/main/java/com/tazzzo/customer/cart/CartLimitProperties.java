package com.tazzzo.customer.cart;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PR-12C — explicit, bounded cart limits (launch: 50 distinct SKUs, 20 units per SKU). */
@ConfigurationProperties(prefix = "tazzzo.customer-cart")
public class CartLimitProperties {

    private int maxDistinctItems = 50;
    private int maxQuantityPerItem = 20;

    public int getMaxDistinctItems() {
        return maxDistinctItems;
    }

    public void setMaxDistinctItems(int maxDistinctItems) {
        this.maxDistinctItems = maxDistinctItems;
    }

    public int getMaxQuantityPerItem() {
        return maxQuantityPerItem;
    }

    public void setMaxQuantityPerItem(int maxQuantityPerItem) {
        this.maxQuantityPerItem = maxQuantityPerItem;
    }

    @PostConstruct
    void validate() {
        // 50 is also the commerce enricher's hard page cap: a larger cart could not be enriched.
        if (maxDistinctItems <= 0 || maxDistinctItems > 50) {
            throw new IllegalStateException("tazzzo.customer-cart.max-distinct-items must be within 1..50");
        }
        if (maxQuantityPerItem <= 0) {
            throw new IllegalStateException("tazzzo.customer-cart.max-quantity-per-item must be > 0");
        }
    }
}

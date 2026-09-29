package com.tazzzo.customer.checkout;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PR-13A — explicit, bounded checkout settings (launch: quotes live 5 minutes). */
@ConfigurationProperties(prefix = "tazzzo.customer-checkout")
public class CheckoutProperties {

    private int quoteTtlSeconds = 300;

    public int getQuoteTtlSeconds() {
        return quoteTtlSeconds;
    }

    public void setQuoteTtlSeconds(int quoteTtlSeconds) {
        this.quoteTtlSeconds = quoteTtlSeconds;
    }

    @PostConstruct
    void validate() {
        if (quoteTtlSeconds < 30 || quoteTtlSeconds > 3600) {
            throw new IllegalStateException("tazzzo.customer-checkout.quote-ttl-seconds must be within 30..3600");
        }
    }
}

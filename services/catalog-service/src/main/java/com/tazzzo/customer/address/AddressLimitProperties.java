package com.tazzzo.customer.address;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PR-12B — the bounded number of active saved addresses per customer. Configurable, launch
 *  default 10 (mission §5); validated at startup rather than left to fail lazily on first use. */
@ConfigurationProperties(prefix = "tazzzo.customer-address")
public class AddressLimitProperties {

    private int maxActiveAddresses = 10;

    public int getMaxActiveAddresses() {
        return maxActiveAddresses;
    }

    public void setMaxActiveAddresses(int maxActiveAddresses) {
        this.maxActiveAddresses = maxActiveAddresses;
    }

    @PostConstruct
    void validate() {
        if (maxActiveAddresses <= 0) {
            throw new IllegalStateException("tazzzo.customer-address.max-active-addresses must be > 0");
        }
    }
}

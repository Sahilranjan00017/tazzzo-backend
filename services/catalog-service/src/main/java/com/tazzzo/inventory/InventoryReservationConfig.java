package com.tazzzo.inventory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** PR-14A — enables {@link InventoryReservationProperties}. */
@Configuration
@EnableConfigurationProperties(InventoryReservationProperties.class)
public class InventoryReservationConfig {
}

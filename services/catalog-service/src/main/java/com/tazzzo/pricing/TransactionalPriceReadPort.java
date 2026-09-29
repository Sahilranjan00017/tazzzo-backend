package com.tazzzo.pricing;

import com.mongodb.client.ClientSession;
import com.tazzzo.common.money.Currency;

import java.util.Collection;
import java.util.Map;

/**
 * PR-14B — a companion to {@link PriceReadPort} for a caller whose read MUST participate in the
 * CALLER's own outer transaction (a future {@code customer.order}'s price revalidation).
 * Deliberately a SEPARATE interface rather than new abstract methods on {@link PriceReadPort}
 * itself: {@code PriceReadPort} is a functional interface today (exactly one abstract method) used
 * as a lambda/simple stub in existing tests, and {@code findCurrentPrices} is a {@code default}
 * method specifically so those stubs keep compiling without implementing it — adding an abstract
 * session-aware method to that interface would break both. {@link PricingService} implements BOTH
 * ports, sharing ONE decode/status algorithm; only the Mongo call shape differs
 * ({@code find(...)} vs {@code find(session, ...)}).
 */
public interface TransactionalPriceReadPort {

    /** Session-aware point read, otherwise identical to {@link PriceReadPort#findCurrentPrice}. */
    PriceLookup findCurrentPrice(ClientSession session, String skuId);

    /** Session-aware batch read, otherwise identical to {@link PriceReadPort#findCurrentPrices}. */
    Map<String, PriceLookup> findCurrentPrices(ClientSession session, Collection<String> skuIds, Currency currency);
}

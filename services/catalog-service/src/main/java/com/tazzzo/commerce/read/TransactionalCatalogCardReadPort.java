package com.tazzzo.commerce.read;

import com.mongodb.client.ClientSession;

import java.util.Optional;

/**
 * PR-14B — a companion to {@link CatalogCardReadPort} for a caller whose read MUST participate in
 * the CALLER's own outer transaction (a future {@code customer.order}'s eligibility/title
 * revalidation). Deliberately a SEPARATE interface rather than a new abstract method on
 * {@link CatalogCardReadPort} itself: that port is a genuine functional interface (exactly one
 * abstract method) used as a lambda in existing call sites — adding a second abstract method would
 * break every one of them. {@link CatalogCardReader} implements BOTH ports, sharing ONE products
 * lookup filter and ONE {@code ConsumerEligibility.isEligible(...)} + {@code Document} → facts
 * mapping; only the Mongo call shape differs ({@code find(...)} vs {@code find(session, ...)}).
 */
public interface TransactionalCatalogCardReadPort {

    /** Session-aware eligible-card read, otherwise identical to
     *  {@link CatalogCardReadPort#findEligibleCard}. */
    Optional<CatalogCardFacts> findEligibleCard(ClientSession session, String skuId);
}

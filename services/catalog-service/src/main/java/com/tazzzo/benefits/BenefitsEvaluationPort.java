package com.tazzzo.benefits;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;

/**
 * The standalone (non-transactional) Benefits evaluation for a consumer that is NOT inside its own transaction.
 * Read only: it owns no transaction and writes nothing. The caller supplies only what Benefits does not own — the
 * customer and the eligible subtotal; the entitlement, plan snapshot and rate are authoritative internal inputs.
 *
 * <p>{@code NoBenefit} is a normal result. An outage or corruption in Membership throws
 * {@link BenefitsFailure}; it is never reported as "no benefit". A caller already inside a {@code Tx.call} must use
 * {@link TransactionalBenefitsEvaluationPort}.
 */
public interface BenefitsEvaluationPort {

    BenefitEvaluation evaluate(CustomerId customerId, Money eligibleSubtotal);
}

package com.tazzzo.benefits;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;

/**
 * The session-aware companion of {@link BenefitsEvaluationPort} for a caller whose evaluation MUST participate in
 * the CALLER's own transaction (a future Checkout/Order money validation).
 *
 * <p>Contract of every implementation: it joins the caller's {@code session}; it opens NO transaction; it writes
 * NOTHING; it emits ZERO metrics (the caller's {@code Tx.call} body may retry, so the operation that owns the
 * transaction owns observability); it never falls back to a standalone evaluation; and a transient transaction
 * error propagates UNTOUCHED so the caller's retry keeps working.
 */
public interface TransactionalBenefitsEvaluationPort {

    BenefitEvaluation evaluate(ClientSession session, CustomerId customerId, Money eligibleSubtotal);
}

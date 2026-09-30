package com.tazzzo.customer.order;

/**
 * PR-15A-1 — the payment condition that was satisfied WHEN the Order was confirmed; a historical fact,
 * not live settlement state.
 *
 * <p>{@code COD_DUE} means: the Order is confirmed, its inventory is consumed, and the customer owes
 * payment on delivery. It does NOT mean payment was received, authorized or captured. A future
 * {@code COD_COLLECTED} is a separate event owned by a future Payment/Settlement domain and never
 * rewrites this field. Only {@code COD_DUE} exists because nothing else has a producer yet.
 */
public enum ConfirmedPaymentCondition {
    COD_DUE
}

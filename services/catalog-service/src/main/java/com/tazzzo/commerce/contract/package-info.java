/**
 * NEUTRAL SHARED CONTRACT LAYER (PR-04 review, STEP 13 clarification).
 *
 * <p>This package holds frozen, dependency-free contract vocabulary — enums
 * ({@link com.tazzzo.commerce.contract.StockState},
 * {@link com.tazzzo.commerce.contract.ImageRole},
 * {@link com.tazzzo.commerce.contract.PublicErrorCode}) and location primitives
 * ({@link com.tazzzo.commerce.contract.Pincode},
 * {@link com.tazzzo.commerce.contract.LocationQuery}). It depends on NOTHING
 * (leaf package) and is therefore safe for BOTH directions of the frozen DAG:
 * domain modules (inventory, pricing, …) may reference this vocabulary, and so
 * may {@code commerce.read}/{@code commerce.api}. That is deliberately different
 * from {@code commerce.read} (composition) and {@code commerce.api} (transport),
 * which NO domain module may depend on — enforced by ArchUnit
 * ({@code ModuleBoundaryTest}).
 *
 * <p>Do not add types with dependencies here; anything that imports a domain,
 * Spring, or persistence class belongs elsewhere.
 */
package com.tazzzo.commerce.contract;

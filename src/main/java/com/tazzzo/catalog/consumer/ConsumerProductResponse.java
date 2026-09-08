package com.tazzzo.catalog.consumer;

import java.util.List;

/**
 * The consumer product projection (RESP-PROJ RP-1). A DISTINCT type, never a filtered
 * {@code ApiDtos.ProductResponse}: filtering a shared DTO means every field a future operator
 * feature adds is consumer-exposed until someone remembers to remove it, and a separate type
 * inverts that default.
 *
 * <p>Absent BY CONSTRUCTION, per RP-1 and RP-7: {@code lifecycle}, {@code classification.status},
 * {@code classification.release_id}, {@code version}, {@code canonical_key},
 * {@code attribute_schema_id}, the unrestricted raw attribute map, and {@code taxonomyPath}.
 *
 * <p>{@code projectionVersion} is ITEM-LOCAL (RP-6c). A category listing spans verticals and policy
 * is keyed per vertical, so no single envelope value could be truthful. It is null exactly when the
 * product's vertical has no projection policy (RP-6b) — never synthesized as 0, because that would
 * make "unauthored" indistinguishable from "authored and empty".
 *
 * <p>NOT a commerce card (RP-8). Price, seller/offer availability, inventory and ranking are
 * separate contracts and read models.
 */
public record ConsumerProductResponse(String id, String title, String brandCode,
                                      List<ConsumerAttributeResponse> attributes,
                                      String projectionVersion) { }

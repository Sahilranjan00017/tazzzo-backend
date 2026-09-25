package com.tazzzo.catalog.consumer;

/**
 * One projected consumer attribute (RESP-PROJ RP-6a). The response is an ORDERED LIST of these, not
 * a map, because display order is an intentional decision of the vertical's projection policy.
 *
 * <p>{@code unit} exists so a structured quantity is ONE entry rather than two. That is what makes
 * RP-4 structural instead of a rule to remember: {@code pack_size} and {@code pack_unit} collapse
 * into a single item carrying both, so a half-formed quantity is not representable in the response
 * shape at all.
 *
 * @param key   the governed attribute key
 * @param label the policy's display label
 * @param value the projected value
 * @param unit  the paired unit, or null when the attribute has none
 */
public record ConsumerAttributeResponse(String key, String label, Object value, String unit) { }

package com.tazzzo.serviceability;

/**
 * The public-facing serviceability view (PR-10B): what a customer location resolves to, carrying
 * the authoritative {@link ServiceArea#version()} for the public contract's {@code serviceAreaVersion}.
 * Deliberately NARROWER than {@link ServiceabilityResolution} — it exposes only {@code serviceable},
 * the public {@code serviceAreaId} and its version, and NEVER a {@code fulfillmentLocationId}
 * (internal routing target). ETA is absent: no authoritative source exists.
 *
 * <ul>
 *   <li>valid PIN with no configured area → {@code (false, null, null)} (the normal outside-coverage answer);</li>
 *   <li>inactive area / no active route → {@code (false, areaId, version)} (not serviceable, area known);</li>
 *   <li>active area with a route → {@code (true, areaId, version)}.</li>
 * </ul>
 */
public record PublicServiceability(boolean serviceable, String serviceAreaId, Long serviceAreaVersion) { }

package com.tazzzo.media;

import java.util.List;

/**
 * Internal command replacing an owner's COMPLETE ordered asset set atomically (STEP 13).
 * Whole-set replacement — never per-image mutations — because ordering and the single-PRIMARY
 * rule are SET-level invariants: independent image edits could expose half-updated ordering or
 * two PRIMARYs mid-sequence; one document replacement can never be observed half-done.
 * {@code expectedVersion} null = create (v1); a value = CAS update to v+1. No public endpoint.
 */
public record UpsertMediaSetCommand(
        MediaOwnerType ownerType,
        String ownerId,
        List<MediaAsset> assets,
        String source,
        Long expectedVersion
) { }

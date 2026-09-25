package com.tazzzo.media;

import com.tazzzo.commerce.contract.ImageRole;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The canonical ordered media for one owner (PR-05). One ACTIVE canonical set per
 * {@code (ownerType, ownerId)} — enforced by the unique Mongo index and by whole-set atomic
 * replacement (a reader can never observe half-updated ordering).
 *
 * <p><b>Frozen ordering invariants (STEP 9):</b> at most ONE {@code PRIMARY}; when a PRIMARY
 * exists it MUST hold {@code sortOrder == 0} (the one consistent rule — primary always sorts
 * first); {@code sortOrder} values are unique within the set; {@code assetId}s are unique within
 * the set; an EMPTY asset list is valid (media deliberately cleared — browse renders the
 * placeholder later); absence of a PRIMARY is representable.
 *
 * <p><b>Contract mapping (STEP 25, for the later Commerce Read PRs):</b> {@code MediaAsset} →
 * {@code ProductImageDto{url(resolved from assetKey), role, order(sortOrder), alt, width, height}};
 * the PRIMARY asset (resolved) → {@code ProductCardDto.thumbnailUrl}; the full ordered list →
 * {@code ProductDetailDto.gallery}. The persisted model is deliberately NOT the DTO shape.
 */
public record MediaSet(
        MediaOwnerType ownerType,
        String ownerId,
        long version,
        boolean active,
        List<MediaAsset> assets
) {
    /** Sanity ceiling per set — a fat-finger guard, not a merchandising rule. */
    static final int MAX_ASSETS = 50;
    /**
     * Technical bound for ownerId — it participates in the unique {@code (owner_type, owner_id)}
     * Mongo index, so it must stay small and clean. Deliberately NOT Catalog TZP grammar:
     * Media stays id-format-agnostic. Policy: non-blank, exactly its own trim (surrounding
     * whitespace is rejected, never silently normalised), no control characters, ≤128 chars.
     */
    static final int MAX_OWNER_ID = 128;

    /**
     * <b>{@code active} ownership (PR-05 review):</b> {@link UpsertMediaSetCommand} NEVER mutates
     * {@code active} (create writes {@code true}; updates leave it untouched). Deactivation /
     * reactivation (delisting an owner's media) will arrive as its own explicit, CAS-guarded,
     * audited lifecycle command when a real need exists — MISSING vs INACTIVE is already part of
     * the read contract, so the field stays. Same pattern as Inventory.
     */
    public MediaSet {
        Objects.requireNonNull(ownerType, "ownerType required");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > MAX_OWNER_ID
                || !ownerId.equals(ownerId.trim())
                || ownerId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(
                    "ownerId required: non-blank, trimmed, no control chars, max " + MAX_OWNER_ID);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(assets, "assets required (empty list = cleared media)");
        if (assets.size() > MAX_ASSETS) {
            throw new IllegalArgumentException("asset count exceeds sanity ceiling " + MAX_ASSETS);
        }
        assets = List.copyOf(assets);

        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        int primaries = 0;
        for (MediaAsset a : assets) {
            if (!ids.add(a.assetId())) {
                throw new IllegalArgumentException("duplicate assetId in set: " + a.assetId());
            }
            // DECIDED (PR-05 review): duplicate assetKey within ONE set is rejected — two
            // entries pointing at the same physical object has no product behavior and is
            // almost certainly a copy-paste error. Reuse of a key ACROSS sets (e.g. PRODUCT
            // and SKU sharing an image) remains allowed.
            if (!keys.add(a.assetKey())) {
                throw new IllegalArgumentException("duplicate assetKey in set: " + a.assetKey());
            }
            if (!orders.add(a.sortOrder())) {
                throw new IllegalArgumentException("duplicate sortOrder in set: " + a.sortOrder());
            }
            if (a.role() == ImageRole.PRIMARY) {
                primaries++;
                if (a.sortOrder() != 0) {
                    throw new IllegalArgumentException("PRIMARY must have sortOrder 0, got " + a.sortOrder());
                }
            }
        }
        if (primaries > 1) {
            throw new IllegalArgumentException("at most one PRIMARY asset allowed, found " + primaries);
        }
    }

    /** The PRIMARY asset, when one exists. Thumbnail resolution starts here. */
    public Optional<MediaAsset> primary() {
        return assets.stream().filter(a -> a.role() == ImageRole.PRIMARY).findFirst();
    }

    /** Deterministic consumer order: ascending sortOrder (PRIMARY, when present, is first at 0). */
    public List<MediaAsset> orderedAssets() {
        return assets.stream().sorted(Comparator.comparingInt(MediaAsset::sortOrder)).toList();
    }
}

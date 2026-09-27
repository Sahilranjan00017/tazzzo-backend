package com.tazzzo.commerce.api;

import com.tazzzo.commerce.api.dto.NodeDto;
import com.tazzzo.commerce.api.dto.NodeListResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PR-10C — deterministic {@code ETag} for {@code /v1/categories} and
 * {@code /v1/categories/{id}/children} ONLY. A content-hash ETag, not a separately-tracked
 * version: it is computed from the ACTUAL response representation (route, node id, resolved
 * release, and the exact visible item set) rather than from {@code resolvedReleaseId} alone.
 *
 * <p><b>Why content-hash, not release-only:</b> the visible node set for a release is NOT fully
 * determined by the release id — {@code ConsumerTaxonomyService.root/children} hides a node whose
 * live consumer-eligibility probe currently misses (TR-4A), and that probe result can change
 * between two requests against the exact same release as products are published/withdrawn. A
 * release-only ETag would therefore serve a stale {@code 304} the moment a category's visibility
 * flips without any release change — silently wrong. Hashing the actual serialized item set makes
 * the ETag correct by construction: any change to what a client would see changes the hash, and
 * nothing changes without changing the hash. No taxonomy refactor is required.
 *
 * <p><b>Never includes:</b> {@code requestId} (changes every response by design — including it
 * would make the ETag always miss) or any internal database version/sequence number (only the
 * already-public {@code resolvedReleaseId} and node id/name pairs are hashed).
 */
final class TaxonomyETag {

    private TaxonomyETag() { }

    static String compute(String route, String nodeId, NodeListResponse body) {
        StringBuilder canonical = new StringBuilder(route).append(" ")
                .append(nodeId == null ? "" : nodeId).append(" ")
                .append(body.resolvedReleaseId()).append(" ");
        for (NodeDto item : body.items()) {
            canonical.append(item.id()).append(":").append(item.name()).append(" ");
        }
        byte[] digest = sha256(canonical.toString());
        return "\"" + HexFormat.of().formatHex(digest) + "\"";
    }

    /** RFC 7232 §3.2: a match against ANY listed value, or {@code *}, is a conditional hit. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            if (candidate.trim().equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.tazzzo.commerce.api;

import com.tazzzo.commerce.api.dto.NodeDto;
import com.tazzzo.commerce.api.dto.NodeListResponse;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * PR-10C — deterministic {@code ETag} for {@code /v1/categories},
 * {@code /v1/categories/{id}/children} and {@code /v1/categories/{id}} ONLY. A content-hash ETag, not a separately-tracked
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
 * <p><b>Canonicalization is structurally unambiguous (final review #1).</b> Fields are NEVER joined
 * with a delimiter character into one string before hashing — a name containing a space or colon
 * could otherwise reproduce the same pre-hash bytes as a differently-shaped item sequence (SHA-256
 * protects the hash from collision, not the encoding from ambiguity). Instead every string is
 * length-prefixed via {@link DataOutputStream#writeUTF(String)} (a 2-byte length then the exact
 * UTF-8 bytes — the string's OWN content can never be misread as a boundary), the {@code nodeId}
 * null/present state is an explicit boolean sentinel (never collapsed to an empty string that could
 * collide with a real empty value), and the item COUNT is written before the items so a shorter
 * sequence can never be reinterpreted as a longer one.
 *
 * <p><b>Never includes:</b> {@code requestId} (changes every response by design — including it
 * would make the ETag always miss) or any internal database version/sequence number (only the
 * already-public {@code resolvedReleaseId} and node id/name pairs are hashed).
 */
final class TaxonomyETag {

    private TaxonomyETag() { }

    static String compute(String route, String nodeId, NodeListResponse body) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(route);
            out.writeBoolean(nodeId != null);           // explicit null sentinel, never "" collapse
            out.writeUTF(nodeId == null ? "" : nodeId);
            out.writeUTF(body.resolvedReleaseId());
            List<NodeDto> items = body.items();
            out.writeInt(items.size());                 // fixes the count BEFORE any item bytes
            for (NodeDto item : items) {
                out.writeUTF(item.id());
                out.writeUTF(item.name());
            }
            out.flush();
            byte[] digest = sha256(buffer.toByteArray());
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream never actually throw I/O errors in memory.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * RFC 7232 §2.3.2 / §3.2 — GET/HEAD conditional requests use WEAK comparison: a listed value
     * matches regardless of its own {@code W/} prefix, as long as the underlying opaque tag is
     * identical to ours. We always EMIT a strong tag (never weakened); only the comparison is weak.
     * A match against ANY listed value, or the wildcard {@code *}, is a conditional hit.
     */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String token : splitEtagList(header)) {
            if (opaqueTag(token).equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /** Strips an optional {@code W/} weak-validator prefix, per weak comparison semantics. */
    private static String opaqueTag(String token) {
        String candidate = token.trim();
        if (candidate.regionMatches(true, 0, "W/", 0, 2)) {
            candidate = candidate.substring(2);
        }
        return candidate;
    }

    /**
     * Splits an {@code If-None-Match} list on top-level commas only (never inside a quoted etag) —
     * a small, dependency-free seam, not a full HTTP grammar parser.
     */
    private static List<String> splitEtagList(String header) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            }
            if (c == ',' && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

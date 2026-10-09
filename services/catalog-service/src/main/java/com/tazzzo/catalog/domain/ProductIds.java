package com.tazzzo.catalog.domain;

import java.util.regex.Pattern;

/**
 * The one canonical product/SKU id grammar: {@code TZP-} followed by 1..40 ASCII letters, digits or hyphens, matched
 * in full. There is NO case normalisation anywhere: lowercase and mixed case are valid and are stored and compared
 * exactly as given. Only URL-unreserved characters, at most 44 in all, so an id is safe in a path segment.
 *
 * <p>The cart, content blocks, the storefront and the published OpenAPI {@code ProductId} all use this grammar; the
 * write paths (create, mint, bundle and pack components, the normal bulk import) enforce it through here, so an id
 * that can be created can always be carted, merchandised and fetched. The Mongo {@code $jsonSchema} ({@code ^TZP-})
 * stays as a coarser backstop.
 */
public final class ProductIds {

    /** The unanchored grammar, for embedding in a larger pattern (e.g. a content link). */
    public static final String BODY = "TZP-[A-Za-z0-9-]{1,40}";

    /** The grammar as an anchored regex string; also the value published in the OpenAPI schemas. */
    public static final String REGEX = "^" + BODY + "$";

    public static final Pattern PATTERN = Pattern.compile(REGEX);

    private ProductIds() { }

    /** Full match only; {@code null} is invalid. A trailing newline is rejected (matches() needs the whole input). */
    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }

    /** @throws IllegalArgumentException with a fixed message that does not echo the input */
    public static String require(String id, String field) {
        if (!isValid(id)) {
            throw new IllegalArgumentException(field + " must match " + REGEX);
        }
        return id;
    }
}

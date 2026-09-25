package com.tazzzo.catalog.consumer;

import com.ibm.icu.lang.UCharacter;

import java.text.Normalizer;
import java.util.Comparator;

/**
 * TR-3's ratified transport ordering: {@code NFKC → trim → collapse whitespace → Unicode
 * case-fold}, ascending lexical comparison of that key, then {@code node_id} ASC as the terminal
 * tiebreaker. Service-owned; it must NOT depend on MongoDB server or default collation.
 *
 * <p><b>This is TRANSPORT ordering only. It is not curated shelf order</b> — not relevance, not
 * merchandising rank, not popularity, not freshness. If a {@code display_order} is added later it
 * becomes a new contract version or ordering mode, and must not be smuggled in as though
 * alphabetic order had always meant merchandising order.
 *
 * <p>The case-fold is ICU4J's {@link UCharacter#foldCase}, not {@code toLowerCase(Locale.ROOT)}.
 * On today's all-ASCII node names the two are indistinguishable; they diverge exactly once
 * non-ASCII names exist, which is the case TR-3 was pinned to survive.
 */
public final class TransportOrder {

    private TransportOrder() {
    }

    public static String sortKey(String name) {
        if (name == null) {
            return "";
        }
        String normalised = Normalizer.normalize(name, Normalizer.Form.NFKC).trim()
                .replaceAll("\\s+", " ");
        return UCharacter.foldCase(normalised, true);
    }

    /**
     * Orders by the sort key, then by id. The id tiebreaker is defence in depth today — sibling
     * names are unique across all 460 seeded nodes, case-insensitively — and still required,
     * because that uniqueness is only enforced against {@code status=active} siblings.
     */
    public static <T> Comparator<T> by(java.util.function.Function<T, String> name,
                                       java.util.function.Function<T, String> id) {
        return Comparator.<T, String>comparing(n -> sortKey(name.apply(n)))
                .thenComparing(n -> id.apply(n) == null ? "" : id.apply(n));
    }
}

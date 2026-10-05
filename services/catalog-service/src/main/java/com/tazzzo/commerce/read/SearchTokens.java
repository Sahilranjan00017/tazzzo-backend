package com.tazzzo.commerce.read;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The ONE normalisation shared by the projector (which stores a card's tokens) and the search read (which turns a
 * query into prefix predicates), so a stored token and a query token can never disagree on casing or splitting.
 * Tokens are lower-cased runs of letters/digits plus the combining marks that follow them (Unicode-aware, so
 * Devanagari words with their matras tokenise as whole words); everything else
 * separates. No stemming, no synonyms: deterministic and index-friendly, nothing more.
 */
public final class SearchTokens {

    public static final int MAX_QUERY_LENGTH = 64;
    public static final int MAX_QUERY_TOKENS = 5;
    public static final int MIN_TOKEN_LENGTH = 2;
    public static final int MAX_TOKEN_LENGTH = 32;
    /** Stored tokens per card are capped so a pathological title cannot bloat the multikey index. */
    static final int MAX_STORED_TOKENS = 40;

    private SearchTokens() { }

    /** Distinct tokens of a card's title and brand code, in first-seen order. */
    public static List<String> of(String title, String brandCode) {
        Set<String> out = new LinkedHashSet<>();
        split(title, out);
        split(brandCode, out);
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_STORED_TOKENS ? List.copyOf(list.subList(0, MAX_STORED_TOKENS)) : List.copyOf(list);
    }

    /**
     * The query tokens a search must ALL match as prefixes, or empty when the query carries nothing searchable.
     *
     * @throws IllegalArgumentException the raw query is longer than {@link #MAX_QUERY_LENGTH}, has more than
     *         {@link #MAX_QUERY_TOKENS} tokens, or a token longer than {@link #MAX_TOKEN_LENGTH}
     */
    public static List<String> query(String raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query too long");
        }
        Set<String> out = new LinkedHashSet<>();
        split(raw, out);
        List<String> tokens = new ArrayList<>();
        for (String t : out) {
            if (t.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("query token too long");
            }
            if (t.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(t);
            }
        }
        if (tokens.size() > MAX_QUERY_TOKENS) {
            throw new IllegalArgumentException("too many query tokens");
        }
        return List.copyOf(tokens);
    }

    /** Vowel signs and similar marks (Devanagari matras, for one) belong to the word they follow. */
    private static boolean isCombiningMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static void split(String text, Set<String> out) {
        if (text == null) {
            return;
        }
        StringBuilder current = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || (current.length() > 0 && isCombiningMark(cp))) {
                current.appendCodePoint(Character.toLowerCase(cp));
            } else if (current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
        });
        if (current.length() > 0) {
            out.add(current.toString());
        }
    }
}

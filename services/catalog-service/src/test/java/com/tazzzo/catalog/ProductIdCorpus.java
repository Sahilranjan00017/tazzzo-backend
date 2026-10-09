package com.tazzzo.catalog;

import java.util.List;

/** The shared accept/reject corpus for the canonical product id grammar {@code ^TZP-[A-Za-z0-9-]{1,40}$}. */
public final class ProductIdCorpus {

    private ProductIdCorpus() { }

    public static final List<String> VALID = List.of(
            "TZP-1", "TZP-MED-3", "TZP-l001", "TZP-Med-3", "TZP-A-B", "TZP--1", "TZP-" + "a".repeat(40));

    public static final List<String> INVALID = List.of(
            "TZP-", "TZP-" + "a".repeat(41), "TZP-a_b", "TZP-a b", "tzp-1", "TZP-1/../x", "TZP-%41",
            "TZP-1\n", "TZP-١" /* ARABIC-INDIC DIGIT ONE */, "TZP-１" /* FULLWIDTH DIGIT ONE */, "XTZP-1", " TZP-1");
}

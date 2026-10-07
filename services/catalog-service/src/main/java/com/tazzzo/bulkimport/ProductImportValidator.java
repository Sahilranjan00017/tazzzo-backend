package com.tazzzo.bulkimport;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.api.ProductController;
import com.tazzzo.catalog.domain.GtinBinding;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.schema.CanonicalKey;
import com.tazzzo.catalog.schema.CanonicalKeyService;
import com.tazzzo.catalog.tx.AttributeViolationException;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates a whole product file before anything is written. It reuses the create path's own rules rather than
 * restating them:
 * <ul>
 *   <li>attribute governance (schema membership, required attributes, value types, claim evidence) through
 *   {@link AttributeGovernanceService#validate};</li>
 *   <li>the {@code products} collection's own {@code $jsonSchema} validator, probed by inserting the exact document mint
 *   would insert inside a transaction that is ALWAYS aborted (nothing is ever committed);</li>
 *   <li>canonical-key derivation through {@link CanonicalKeyService#derive}.</li>
 * </ul>
 * An import is stricter than a single create in three ways a launch catalogue needs: the release must exist, the vertical
 * must be a real taxonomy vertical (or one of the two review sentinels), and a GTIN must pass its GS1 check digit.
 */
public final class ProductImportValidator {

    record Checked(List<ProductDraft> drafts, Set<String> unchanged) { }

    /**
     * Per-row outcomes without the all-or-nothing rule: {@code drafts} holds the draft of every valid row by its index
     * (an UNCHANGED row is valid and is also listed in {@code unchanged}); {@code errors} holds every invalid row. The
     * asynchronous import job validates in bounded batches and records each row's outcome, so it needs the whole picture.
     */
    public record RowChecks(java.util.Map<Integer, ProductDraft> drafts, Set<String> unchanged, List<BulkImportDtos.RowError> errors) { }

    private final MongoDatabase db;
    private final MongoClient client;
    private final AttributeGovernanceService governance;
    private final CanonicalKeyService canonicalKeys;

    public ProductImportValidator(MongoDatabase db, MongoClient client, AttributeGovernanceService governance,
                                  CanonicalKeyService canonicalKeys) {
        this.db = db;
        this.client = client;
        this.governance = governance;
        this.canonicalKeys = canonicalKeys;
    }

    Checked validate(List<CreateProductRequest> rows) {
        RowChecks checks = validateRows(rows);
        if (!checks.errors().isEmpty()) {
            throw new ImportRejectedException(checks.errors().size() + " row(s) are invalid; nothing was written", checks.errors());
        }
        return new Checked(new ArrayList<>(checks.drafts().values()), checks.unchanged());
    }

    /** The same checks as {@link #validate}, reported per row instead of rejecting the whole list. */
    public RowChecks validateRows(List<CreateProductRequest> rows) {
        List<BulkImportDtos.RowError> errors = new ArrayList<>();
        java.util.Map<Integer, ProductDraft> drafts = new java.util.TreeMap<>();
        Set<String> unchanged = new TreeSet<>();
        Map<String, Integer> ids = new HashMap<>(), keys = new HashMap<>(), gtins = new HashMap<>(), canon = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            CreateProductRequest r = rows.get(i);
            String problem;
            ProductDraft d = null;
            try {
                problem = shape(r);
                if (problem == null) {
                    d = ProductController.toDraft(r);
                    problem = classification(d);
                }
            } catch (RuntimeException e) {
                problem = "row could not be read: " + e.getClass().getSimpleName();
            }
            if (problem != null) {
                errors.add(new BulkImportDtos.RowError(i, "INVALID_ROW", problem));
                continue;
            }
            String dup = firstDuplicate(i, d, ids, keys, gtins);
            if (dup != null) {
                errors.add(new BulkImportDtos.RowError(i, "DUPLICATE_ROW", dup));
                continue;
            }
            Document existing = db.getCollection("products").find(Filters.eq("_id", d.id())).first();
            if (existing != null) {
                if (sameCreatePayload(existing, d)) {
                    unchanged.add(d.id());
                    drafts.put(i, d);
                } else {
                    errors.add(new BulkImportDtos.RowError(i, "CONFLICT",
                            "product " + d.id() + " already exists with a different create payload"));
                }
                continue;
            }
            try {
                governance.validate(d.verticalId(), d.attributes(), d.evidenceRefs());
            } catch (AttributeViolationException e) {
                errors.add(new BulkImportDtos.RowError(i, "INVALID_ROW", e.getMessage()));
                continue;
            }
            String owned = ownedElsewhere(d);
            if (owned != null) {
                errors.add(new BulkImportDtos.RowError(i, "CONFLICT", owned));
                continue;
            }
            Optional<CanonicalKey> ck = canonicalKeys.derive(d.verticalId(), d.brandCode(), d.productType(), d.attributes(),
                    d.packOf());
            if (ck.isPresent()) {
                Integer prev = canon.putIfAbsent(ck.get().key(), i);
                if (prev != null) {
                    errors.add(new BulkImportDtos.RowError(i, "DUPLICATE_ROW",
                            "same canonical identity as row " + prev + " (same brand, vertical and identity attributes)"));
                    continue;
                }
                if (db.getCollection("canonical_keys").find(Filters.eq("_id", ck.get().key())).first() != null) {
                    errors.add(new BulkImportDtos.RowError(i, "CONFLICT", "canonical identity already belongs to another product"));
                    continue;
                }
            }
            String contract = contractProbe(d);
            if (contract != null) {
                errors.add(new BulkImportDtos.RowError(i, "INVALID_ROW", contract));
                continue;
            }
            drafts.put(i, d);
        }
        return new RowChecks(drafts, unchanged, errors);
    }

    /** Required fields and the import's supported scope (single products; packs and bundles reference other products). */
    static String shape(CreateProductRequest r) {
        if (r == null) return "row is empty";
        for (Object[] f : new Object[][]{{"id", r.id()}, {"productType", r.productType()}, {"identityType", r.identityType()},
                {"brandCode", r.brandCode()}, {"title", r.title()}, {"verticalId", r.verticalId()}, {"releaseId", r.releaseId()},
                {"classificationStatus", r.classificationStatus()}}) {
            if (f[1] == null || ((String) f[1]).isBlank()) return f[0] + " is required";
        }
        if (!"single".equals(r.productType())) {
            return "productType must be single (variant packs and bundles reference other products: create them with "
                    + "POST /api/v1/products once their components exist)";
        }
        if ("internal".equals(r.identityType())) {
            if (r.internalKey() == null || r.internalKey().isBlank()) return "internalKey is required for internal identity";
        } else if ("gtin".equals(r.identityType())) {
            if (r.gtins() == null || r.gtins().isEmpty()) return "gtins are required for gtin identity";
        } else {
            return "identityType must be internal or gtin";
        }
        if (r.gtins() != null) {
            for (var g : r.gtins()) {
                if (g == null || !validGtin(g.value())) return "gtin " + (g == null ? null : g.value()) + " fails the GS1 check digit";
            }
        }
        return null;
    }

    /** GS1 mod-10 over GTIN-8/12/13/14. */
    static boolean validGtin(String v) {
        if (v == null || !v.matches("\\d{8}|\\d{12}|\\d{13}|\\d{14}")) return false;
        int sum = 0;
        for (int i = v.length() - 2, w = 3; i >= 0; i--, w = 4 - w) sum += (v.charAt(i) - '0') * w;
        return (10 - sum % 10) % 10 == v.charAt(v.length() - 1) - '0';
    }

    private String classification(ProductDraft d) {
        if (db.getCollection("catalogue_releases").find(Filters.eq("_id", d.releaseId())).first() == null) {
            return "release " + d.releaseId() + " does not exist";
        }
        if (MintService.UNCLASSIFIED.equals(d.verticalId()) || MintService.SCOPE_BLOCKED.equals(d.verticalId())) return null;
        Document v = db.getCollection("taxonomy_nodes").find(Filters.and(Filters.eq("_id", d.verticalId()),
                Filters.eq("node_type", "vertical"))).first();
        return v == null ? "vertical " + d.verticalId() + " is not a taxonomy vertical" : null;
    }

    private static String firstDuplicate(int i, ProductDraft d, Map<String, Integer> ids, Map<String, Integer> keys,
                                         Map<String, Integer> gtins) {
        Integer p = ids.putIfAbsent(d.id(), i);
        if (p != null) return "product id " + d.id() + " already appears in row " + p;
        if (d.internalKey() != null && "internal".equals(d.identityType())) {
            p = keys.putIfAbsent(d.internalKey(), i);
            if (p != null) return "internalKey already appears in row " + p;
        }
        if (d.gtins() != null) {
            for (GtinBinding g : d.gtins()) {
                p = gtins.putIfAbsent(g.value(), i);
                if (p != null) return "gtin " + g.value() + " already appears in row " + p;
            }
        }
        return null;
    }

    private String ownedElsewhere(ProductDraft d) {
        if ("internal".equals(d.identityType())
                && db.getCollection("identity_keys").find(Filters.eq("_id", d.internalKey())).first() != null) {
            return "internalKey already belongs to another product";
        }
        if (d.gtins() != null) {
            for (GtinBinding g : d.gtins()) {
                if (db.getCollection("gtin_registry").find(Filters.eq("_id", g.value())).first() != null) {
                    return "gtin " + g.value() + " already belongs to another product";
                }
            }
        }
        return null;
    }

    /** The create-time fields of a stored product equal the row: re-submitting a file is a no-op for that row. */
    static boolean sameCreatePayload(Document p, ProductDraft d) {
        Document want = ProductDocuments.fromDraft(d);
        Document c = p.get("classification", Document.class), wc = want.get("classification", Document.class);
        Document id = p.get("identity", Document.class), wid = want.get("identity", Document.class);
        return Objects.equals(p.getString("product_type"), want.getString("product_type"))
                && Objects.equals(p.getString("brand_code"), want.getString("brand_code"))
                && Objects.equals(p.getString("title"), want.getString("title"))
                && id != null && Objects.equals(id.getString("type"), wid.getString("type"))
                && Objects.equals(id.getString("internal_key"), wid.getString("internal_key"))
                && c != null && Objects.equals(c.getString("vertical_id"), wc.getString("vertical_id"))
                && Objects.equals(c.getString("release_id"), wc.getString("release_id"))
                && Objects.equals(c.getString("status"), wc.getString("status"))
                && Objects.equals(p.get("attributes", Document.class), want.get("attributes", Document.class))
                && Objects.equals(gtinValues(p), gtinValues(want));
    }

    private static Set<String> gtinValues(Document p) {
        Set<String> out = new TreeSet<>();
        List<Document> g = p.getList("gtins", Document.class);
        if (g != null) g.forEach(x -> out.add(x.getString("value")));
        return out;
    }

    /** Inserts the exact mint document inside a transaction that is always aborted: the collection validator decides. */
    private String contractProbe(ProductDraft d) {
        try (ClientSession s = client.startSession()) {
            s.startTransaction();
            try {
                db.getCollection("products").insertOne(s, ProductDocuments.fromDraft(d));
                return null;
            } catch (MongoWriteException e) {
                return e.getError().getCode() == 121 ? "row fails the products document contract (validator)"
                        : e.getError().getCode() == 11000 ? "product id already exists" : "row could not be checked";
            } finally {
                if (s.hasActiveTransaction()) s.abortTransaction();
            }
        }
    }
}

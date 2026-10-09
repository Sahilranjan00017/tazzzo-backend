package com.tazzzo.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import com.tazzzo.catalog.api.ApiDtos.*;
import com.tazzzo.catalog.domain.BundleComponent;
import com.tazzzo.catalog.domain.GtinBinding;
import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.domain.ProductIds;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.TaxonomyService;
import com.tazzzo.catalog.tx.*;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Thin transport over proven services. This controller contains NO catalogue rules: it maps
 * HTTP to typed commands, calls one application service, and maps the result back. Every
 * rejection comes from the domain, never from here.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final MintService mintService;
    private final ClassifyService classifyService;
    private final PublishService publishService;
    private final GtinBindService gtinBindService;
    private final BundleService bundleService;
    private final VariantPackService variantPackService;
    private final MergeService mergeService;
    private final ProductUpdateService productUpdateService;
    private final TaxonomyService taxonomyService;
    private final ProductQueryService productQueryService;
    private final ProductLifecycleService lifecycle;

    public ProductController(MintService mintService, ClassifyService classifyService,
                             PublishService publishService, GtinBindService gtinBindService,
                             BundleService bundleService, VariantPackService variantPackService,
                             MergeService mergeService,
                             TaxonomyService taxonomyService, ProductUpdateService productUpdateService,
                             ProductQueryService productQueryService,
                             ProductLifecycleService lifecycle) {
        this.mintService = mintService;
        this.classifyService = classifyService;
        this.publishService = publishService;
        this.gtinBindService = gtinBindService;
        this.bundleService = bundleService;
        this.variantPackService = variantPackService;
        this.mergeService = mergeService;
        this.taxonomyService = taxonomyService;
        this.productUpdateService = productUpdateService;
        this.productQueryService = productQueryService;
        this.lifecycle = lifecycle;
    }

    // NOTE (review M5): no Idempotency-Key parameter is published, because key-based
    // replay-returns-original is NOT implemented. Retry semantics today: a replayed create
    // collides on the identity registry and returns 409 IDENTITY_COLLISION.
    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestBody CreateProductRequest body,
                                    HttpServletRequest httpRequest) {
        ProductDraft draft = toDraft(body);
        if ("bundle".equals(body.productType())) {
            bundleService.writeBundle(AdminActors.require(httpRequest), draft);
        } else if ("variant_pack".equals(body.productType())) {
            variantPackService.writeVariantPack(AdminActors.require(httpRequest), draft);   // F-5: no longer falls through to mint
        } else {
            mintService.mint(AdminActors.require(httpRequest), draft);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(read(body.id()));
    }

    /**
     * Admin product list: ascending id order, keyset-paged by {@code cursor} (the last id of the previous page).
     * Filters: {@code verticalId}; {@code lifecycle} and {@code status} (classification status) require a
     * {@code verticalId} so no query is a collection scan on a secondary filter. Unknown or repeated parameters are 400.
     * (A request carrying {@code canonicalKey} is the identity lookup below, never this list.)
     */
    @GetMapping
    public ProductListResponse list(HttpServletRequest request) {
        AdminListParams q = AdminListParams.read(request, java.util.Set.of("verticalId", "lifecycle", "status"));
        if ((q.get("lifecycle") != null || q.get("status") != null) && q.get("verticalId") == null) {
            throw new IllegalArgumentException("lifecycle and status filters require verticalId");
        }
        List<Document> rows = productQueryService.list(q.get("verticalId"), q.get("lifecycle"), q.get("status"),
                q.cursor(), q.limit() + 1);
        boolean more = rows.size() > q.limit();
        List<Document> page = more ? rows.subList(0, q.limit()) : rows;
        List<ProductSummary> items = page.stream().map(p -> {
            Document c = p.get("classification", Document.class);
            return new ProductSummary(p.getString("_id"), p.getString("product_type"), p.getString("lifecycle"),
                    p.getString("brand_code"), p.getString("title"), c == null ? null : c.getString("vertical_id"),
                    c == null ? null : c.getString("status"), p.getInteger("version", 0));
        }).toList();
        return new ProductListResponse(items, more ? page.get(page.size() - 1).getString("_id") : null);
    }

    /** CAT-ID-4 — identity resolution by canonical key (404 when unbound). */
    @GetMapping(params = "canonicalKey")
    public ProductResponse getByCanonicalKey(@RequestParam String canonicalKey) {
        return toResponse(productQueryService.findByCanonicalKey(canonicalKey));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable @Schema(pattern = ProductIds.REGEX) String id) {
        return read(id);
    }

    /** Optimistic concurrency: If-Match carries the product version; stale -> STALE_VERSION. */
    @PatchMapping("/{id}")
    public ProductResponse patch(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                 @RequestHeader("If-Match") int expectedVersion,
                                 @RequestBody PatchProductRequest body,
                                    HttpServletRequest httpRequest) {
        productUpdateService.updateTitle(AdminActors.require(httpRequest), id, expectedVersion, body.title());
        return read(id);
    }

    @PostMapping("/{id}/classify")
    public ProductResponse classify(@PathVariable @Schema(pattern = ProductIds.REGEX) String id, @RequestBody ClassifyRequest body,
                                    HttpServletRequest httpRequest) {
        classifyService.classify(AdminActors.require(httpRequest), id, body.verticalId(), body.releaseId(), body.status(),
                body.confidence() == null ? 1.0 : body.confidence(),
                body.evidenceRefs() == null ? List.of() : body.evidenceRefs());
        return read(id);
    }

    @PostMapping("/{id}/publish")
    public ProductResponse publish(@PathVariable @Schema(pattern = ProductIds.REGEX) String id, @RequestBody PublishClaimRequest body,
                                    HttpServletRequest httpRequest) {
        publishService.publishClaim(AdminActors.require(httpRequest), id, body.attributeKey(),
                body.evidenceRefs() == null ? List.of() : body.evidenceRefs());
        return read(id);
    }

    @PostMapping("/{id}/gtins")
    public ProductResponse bindGtin(@PathVariable @Schema(pattern = ProductIds.REGEX) String id, @RequestBody GtinBindRequest body,
                                    HttpServletRequest httpRequest) {
        gtinBindService.bind(AdminActors.require(httpRequest), id, body.gtin(), body.market());
        return read(id);
    }

    @PostMapping("/{id}/activate")
    public ProductResponse activate(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                    @RequestHeader("If-Match") int expectedVersion,
                                    HttpServletRequest httpRequest) {
        lifecycle.activate(AdminActors.require(httpRequest), id, expectedVersion);
        return read(id);
    }

    @PostMapping("/{id}/retire")
    public ProductResponse retire(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                  @RequestHeader("If-Match") int expectedVersion,
                                  @RequestBody(required = false) RetireRequest body,
                                    HttpServletRequest httpRequest) {
        lifecycle.discontinue(AdminActors.require(httpRequest), id, expectedVersion, body == null ? null : body.reason());
        return read(id);
    }

    @PostMapping("/{id}/revive")
    public ProductResponse revive(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                  @RequestHeader("If-Match") int expectedVersion,
                                  @RequestBody(required = false) ReviveRequest body,
                                    HttpServletRequest httpRequest) {
        lifecycle.revive(AdminActors.require(httpRequest), id, expectedVersion, body == null ? null : body.formulationVersion());
        return read(id);
    }

    @PostMapping("/{id}/archive")
    public ProductResponse archive(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                   @RequestHeader("If-Match") int expectedVersion,
                                    HttpServletRequest httpRequest) {
        lifecycle.archive(AdminActors.require(httpRequest), id, expectedVersion);
        return read(id);
    }

    /** Merge is ASYNC by contract: the core txn commits, the finalizer completes it. */
    @PostMapping("/{id}/merge/{survivorId}")
    public ResponseEntity<AcceptedResponse> merge(@PathVariable @Schema(pattern = ProductIds.REGEX) String id,
                                                  @PathVariable @Schema(pattern = ProductIds.REGEX) String survivorId,
                                    HttpServletRequest httpRequest) {
        mergeService.startMerge(AdminActors.require(httpRequest), id, survivorId);
        return ResponseEntity.accepted().body(new AcceptedResponse("merging",
                "outbox committed; finalizer completes repointing asynchronously"));
    }

    /** The single create mapping, shared with the bulk product import so a bulk row means exactly what a create means. */
    public static ProductDraft toDraft(CreateProductRequest b) {
        // the canonical id grammar, before anything is built or written (400 MALFORMED_REQUEST)
        ProductIds.require(b.id(), "id");
        if (b.bundleContents() != null) {
            for (var c : b.bundleContents()) {
                ProductIds.require(c == null ? null : c.componentProductId(), "componentProductId");
            }
        }
        if (b.packOf() != null) {
            ProductIds.require(b.packOf().componentProductId(), "packOf.componentProductId");
        }
        List<GtinBinding> gtins = b.gtins() == null ? null
                : b.gtins().stream().map(g -> new GtinBinding(g.value(), g.market())).toList();
        List<BundleComponent> components = b.bundleContents() == null ? null
                : b.bundleContents().stream()
                    .map(c -> new BundleComponent(c.componentProductId(), c.qty(), c.verticalIdSnapshot()))
                    .toList();
        PackOf packOf = b.packOf() == null ? null
                : new PackOf(b.packOf().componentProductId(), b.packOf().qty());
        return new ProductDraft(b.id(), b.productType(), b.identityType(), b.internalKey(), gtins,
                b.brandCode(), b.title(), b.verticalId(), b.releaseId(), b.classificationStatus(),
                b.attributes() == null ? Map.of() : b.attributes(),
                b.evidenceRefs() == null ? List.of() : b.evidenceRefs(), components, packOf);
    }

    private ProductResponse read(String id) {
        return toResponse(productQueryService.requireProduct(id));
    }

    /** CAT-ID-4: shared so a canonical-key lookup returns exactly the same shape as GET /{id}. */
    private ProductResponse toResponse(Document p) {
        Document c = p.get("classification", Document.class);
        String verticalId = c == null ? null : c.getString("vertical_id");
        String path = verticalId == null ? null : taxonomyService.renderPath(verticalId);
        Map<String, Object> classification = c == null ? Map.of() : Map.of(
                "verticalId", String.valueOf(c.getString("vertical_id")),
                "releaseId", String.valueOf(c.getString("release_id")),
                "status", String.valueOf(c.getString("status")));
        return new ProductResponse(p.getString("_id"), p.getString("product_type"),
                p.getString("lifecycle"), p.getString("brand_code"), p.getString("title"),
                classification, p.get("attributes", Document.class),
                p.getInteger("version"), path);
    }
}

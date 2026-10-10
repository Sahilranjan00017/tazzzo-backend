package com.tazzzo.catalog.api.docs;

import com.tazzzo.catalog.api.HttpPlatformProperties;
import com.tazzzo.catalog.api.SurfaceClassifier;
import com.tazzzo.catalog.api.SurfaceClassifier.Surface;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DOCUMENTATION-ONLY post-processing of the generated OpenAPI document (contract-correctness PR). It changes what
 * {@code /v3/api-docs} and {@code docs/openapi.json} SAY; it never changes what the service DOES: no handler, filter,
 * binding, status or envelope is touched, and every path, method and operationId is left exactly as generated.
 *
 * <p>What it adds, all derived from the code's own classification rather than hand-listed per route where possible:
 * <ul>
 *   <li>real success statuses (201/202/204 and the 201-or-200 upserts) and the error responses each surface can return,
 *       with the envelope each surface really writes (admin nested {@code {error:{code,message,request_id}}}, public flat
 *       {@code request_id}, domain {@code requestId} per route family) defined once under {@code components};</li>
 *   <li>security schemes and a per-operation {@code security} derived from {@link SurfaceClassifier};</li>
 *   <li>the body bounds (413), the one documented admin product list/lookup operation, JSON media types instead of
 *       {@code *}{@code /*}, and {@code required} for primitive response properties.</li>
 * </ul>
 * The {@code /v1/**} statuses come from {@link V1StatusCatalog}, which {@code OpenApiContractIT} pins to the hand-written
 * {@code docs/api/v1/openapi.yaml}.
 */
@Component
public class OpenApiContractCustomizer implements OpenApiCustomizer {

    public static final String ADMIN_BEARER = "adminBearer";
    public static final String CUSTOMER_BEARER = "customerBearer";
    public static final String TRUSTED_CALLER_NAME = "trustedCallerName";
    public static final String TRUSTED_CALLER_SECRET = "trustedCallerSecret";

    static final String JSON = "application/json";
    private static final String SCHEMAS = "#/components/schemas/";

    /** Upserts answer 201 for the first write (no {@code expectedVersion}) and 200 for an update; the rest are single codes. */
    private static final Map<String, int[]> ADMIN_SUCCESS = new LinkedHashMap<>();

    static {
        ADMIN_SUCCESS.put("POST /api/v1/products", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/products/{}/merge/{}", new int[]{202});
        ADMIN_SUCCESS.put("POST /api/v1/taxonomy/releases", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/taxonomy/nodes", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/attributes", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/evidence", new int[]{201, 200});
        ADMIN_SUCCESS.put("POST /api/v1/evidence/{}/retract", new int[]{202});
        ADMIN_SUCCESS.put("PUT /api/v1/admin/delivery-slots/{}/{}", new int[]{201, 200});
        ADMIN_SUCCESS.put("PUT /api/v1/admin/inventory/{}/{}", new int[]{201, 200});
        ADMIN_SUCCESS.put("PUT /api/v1/admin/prices/{}", new int[]{201, 200});
        ADMIN_SUCCESS.put("PUT /api/v1/admin/service-areas/{}", new int[]{201, 200});
        ADMIN_SUCCESS.put("PUT /api/v1/admin/media/{}/{}", new int[]{201, 200});
        ADMIN_SUCCESS.put("POST /api/v1/admin/media/uploads", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/admin/content/blocks", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/admin/content/uploads", new int[]{201});
        ADMIN_SUCCESS.put("POST /api/v1/admin/imports/jobs", new int[]{201});
    }

    /**
     * Write operations WITHOUT a path variable whose body names another entity that may not exist (404). Every other
     * variable-less write was read and cannot answer 404: app-config PUT, taxonomy release open (basedOn is not looked up),
     * product create (unknown vertical or component is a 422), evidence create, attribute create, the four import
     * routes (an unknown sku is a rejected row, 422), content upload, block create and reorder (a changed block is a 409).
     */
    private static final Map<String, String> ADMIN_BODY_REFERENCED_404 = Map.of(
            "POST /api/v1/admin/media/uploads", "unknown ownerId: ProductQueryService.requireProduct -> ProductNotFoundException -> 404 NOT_FOUND",
            "POST /api/v1/taxonomy/nodes", "unknown parentId or attributeSchemaId: TaxonomyChangeService -> NODE_NOT_FOUND / UNKNOWN_SCHEMA -> 404");

    /** Admin routes whose handlers can answer 503 (storage, timeout or store outage with its own code). */
    private static final List<String> ADMIN_503_PREFIXES = List.of("/api/v1/admin/media", "/api/v1/admin/content/uploads",
            "/api/v1/admin/orders", "/api/v1/admin/support", "/api/v1/admin/dashboard", "/api/v1/admin/inventory");

    private static final Map<Integer, String> REASON = Map.of(200, "OK", 201, "Created", 202, "Accepted", 204, "No Content");

    private final SchemaNameGuard guard;
    private final HttpPlatformProperties http;

    public OpenApiContractCustomizer(SchemaNameGuard guard, HttpPlatformProperties http) {
        this.guard = guard;
        this.http = http;
    }

    @Override
    public void customise(OpenAPI api) {
        Components components = api.getComponents() == null ? new Components() : api.getComponents();
        api.setComponents(components);
        info(api);
        securitySchemes(components);
        errorEnvelopes(components);
        if (api.getPaths() != null) {
            for (Map.Entry<String, PathItem> path : api.getPaths().entrySet()) {
                for (Map.Entry<PathItem.HttpMethod, Operation> op : path.getValue().readOperationsMap().entrySet()) {
                    operation(path.getKey(), op.getKey().name(), op.getValue());
                }
            }
            adminProductListAndLookup(api);
            declareListParameters(api);
        }
        requiredPrimitives(api, components);
        dropUnusedJsonNode(api, components);
    }

    // ------------------------------------------------------------------ info and components

    private void info(OpenAPI api) {
        io.swagger.v3.oas.models.info.Info info = api.getInfo() == null ? new io.swagger.v3.oas.models.info.Info() : api.getInfo();
        info.setTitle("Tazzzo Catalog Service API");
        info.setDescription("""
                Generated from the controllers (never hand-edited). Three surfaces share this service:

                * **Admin / internal** (`/api/**`, this document's `adminBearer`): Google OIDC human sessions (allowlisted, \
                hosted domain) or service tokens (`read`, `cms`). Roles: `reader`, `cms-writer`, `audit-reader`, `order-ops`, \
                `support-agent`; the `/api/v1/admin/orders` and `/api/v1/admin/support` namespaces are human-staff only. \
                Errors use the NESTED envelope `{ "error": { "code", "message", "request_id" } }` (`AdminErrorEnvelope`).
                * **App / customer** (`/v1/**`): public reads and OTP/session auth need no bearer; `/v1/customer/**` needs the \
                customer access token (`customerBearer`). The app contract with per-operation error codes is \
                `docs/api/v1/openapi.yaml`; the statuses listed here are the same set. Domain errors of the commerce read, cart, \
                checkout, order, address, profile, OTP, session, support, account-deletion and content families use a FLAT body \
                with camelCase `requestId` (`V1ErrorEnvelope` and its variants). Errors written by platform filters and framework \
                fallbacks (413, an undecodable query string, an unmapped failure) use a FLAT body with snake_case `request_id` \
                (`PlatformErrorEnvelope`). Both spellings carry the same id as the `X-Request-Id` response header; they are \
                documented per route family, not unified.
                * **Legacy mobile namespace** (`/catalog/v1/**`): public, flat `PlatformErrorEnvelope` (`request_id`).

                **Request bodies are bounded** and refused with `413 PAYLOAD_TOO_LARGE` before authentication: %s on every \
                route, %s on the bulk-import routes under `/api/v1/admin/imports/**` (500 rows per file), %s on the content-block \
                writes (`POST /api/v1/admin/content/blocks`, `PUT /api/v1/admin/content/blocks/{id}`; a legal document body is up to \
                60,000 characters); the configurable hard maximum is 16 MiB. A chunked import or content-block body is counted as it \
                is read.

                **Reading this document.** Each operation lists the success statuses its handler really returns (201 or 200 for \
                upserts, 202, 204) and the error statuses its surface can produce; framework-level 405/406/415 answers are not \
                repeated on every admin operation. Properties of a JSON response whose Java type is a primitive are `required`; no \
                other property is marked required or nullable because the code base carries no nullability annotations, so \
                absence of `required` means "not guaranteed", not "optional by contract".
                """.formatted(kib(http.getMaxRequestBodyBytes()), kib(http.getBulkImportMaxRequestBodyBytes()),
                kib(http.getContentBlockMaxRequestBodyBytes())));
        api.setInfo(info);
    }

    private static String kib(long bytes) {
        return bytes % (1024 * 1024) == 0 ? bytes / (1024 * 1024) + " MiB (" + bytes + " bytes)"
                : bytes % 1024 == 0 ? bytes / 1024 + " KiB (" + bytes + " bytes)" : bytes + " bytes";
    }

    private static void securitySchemes(Components c) {
        c.addSecuritySchemes(ADMIN_BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .description("`Authorization: Bearer <token>`: a Google OIDC ID token of an allowlisted staff member, or a service "
                        + "token (`read` for GET, `cms` for writes). Missing, invalid or expired credentials answer 401, an "
                        + "authenticated identity whose role may not perform the operation answers 403."));
        c.addSecuritySchemes(CUSTOMER_BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .description("`Authorization: Bearer <customer access token>` issued by `POST /v1/auth/session` and "
                        + "`POST /v1/auth/refresh`. The customer id is always taken from the token, never from the request."));
        c.addSecuritySchemes(TRUSTED_CALLER_NAME, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name("X-Tazzzo-Caller")
                .description("Optional, with `X-Tazzzo-Caller-Secret`: a configured server-side caller (the storefront) is admitted "
                        + "against its own rate-limit bucket instead of its egress IP's. It grants no capability and a wrong "
                        + "or partial credential is silently treated as anonymous, never as an authentication failure."));
        c.addSecuritySchemes(TRUSTED_CALLER_SECRET, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name("X-Tazzzo-Caller-Secret")
                .description("Shared secret half of the trusted-caller pair (see `trustedCallerName`)."));
    }

    private static Schema<?> string(String description) {
        return new StringSchema().description(description);
    }

    private static void errorEnvelopes(Components c) {
        c.addSchemas("AdminErrorBody", new ObjectSchema().description("Inner object of the admin envelope.")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Human-readable, generic message."))
                .addProperty("request_id", string("Request id, also the X-Request-Id response header."))
                .addProperty("correlation_id", string("Caller-supplied correlation id, present only when the request carried one."))
                .required(List.of("code", "message", "request_id")));
        c.addSchemas("AdminErrorEnvelope", new ObjectSchema()
                .description("Error body of every `/api/**` operation (admin / internal surface): NESTED, snake_case `request_id`.")
                .addProperty("error", new Schema<>().$ref(SCHEMAS + "AdminErrorBody"))
                .required(List.of("error")));
        c.addSchemas("PlatformErrorEnvelope", new ObjectSchema()
                .description("FLAT error body written by platform filters and framework fallbacks on the public and customer "
                        + "surfaces (413 PAYLOAD_TOO_LARGE, undecodable query string, unmapped failures) and by every "
                        + "`/catalog/v1/**` error. snake_case `request_id`; domain errors of `/v1/**` use camelCase `requestId` "
                        + "(`V1ErrorEnvelope` and variants).")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Generic message."))
                .addProperty("request_id", string("Request id, also the X-Request-Id response header."))
                .required(List.of("code", "message", "request_id")));
        c.addSchemas("V1ErrorEnvelope", new ObjectSchema()
                .description("FLAT domain error body of the `/v1/auth/**` (session) and `/v1/customer/**` families: camelCase `requestId`.")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Generic message."))
                .addProperty("requestId", string("Request id, also the X-Request-Id response header."))
                .required(List.of("code", "message", "requestId")));
        c.addSchemas("V1PublicReadErrorEnvelope", new ObjectSchema()
                .description("FLAT domain error body of the public commerce reads and content (`/v1/categories*`, `/v1/products*`, "
                        + "`/v1/search`, `/v1/serviceability`, `/v1/content/*`, `/v1/app-config`): camelCase `requestId` plus `retryable`.")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Generic message."))
                .addProperty("requestId", string("Request id, also the X-Request-Id response header."))
                .addProperty("retryable", new Schema<Boolean>().type("boolean").description("Whether the same request may succeed later."))
                .addProperty("retryAfterSeconds", new IntegerSchema().description("Present on 429."))
                .required(List.of("code", "message", "requestId")));
        c.addSchemas("V1OtpErrorEnvelope", new ObjectSchema()
                .description("FLAT domain error body of `/v1/auth/otp/*`: camelCase `requestId` plus `retryAfterSeconds` on throttling.")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Generic message."))
                .addProperty("requestId", string("Request id, also the X-Request-Id response header."))
                .addProperty("retryAfterSeconds", new IntegerSchema().description("Present when the caller is throttled."))
                .required(List.of("code", "message", "requestId")));
        c.addSchemas("V1CheckoutErrorEnvelope", new ObjectSchema()
                .description("FLAT domain error body of `/v1/customer/checkout/*`: camelCase `requestId` plus the offending lines.")
                .addProperty("code", string("Stable machine-readable code."))
                .addProperty("message", string("Generic message."))
                .addProperty("requestId", string("Request id, also the X-Request-Id response header."))
                .addProperty("items", new ArraySchema().items(new ObjectSchema()
                        .addProperty("skuId", string("The line that failed."))
                        .addProperty("reason", string("Why it failed."))).description("Present on line-level failures."))
                .required(List.of("code", "message", "requestId")));
    }

    // ------------------------------------------------------------------ per operation

    private void operation(String path, String method, Operation op) {
        Surface surface = SurfaceClassifier.classify(path.replaceAll("\\{[^}]*}", "x"));
        normaliseMediaTypes(op, path);
        responses(path, method, surface, op);
        security(path, surface, op);
        bodyBound(path, method, op);
    }

    private static void normaliseMediaTypes(Operation op, String path) {
        if (op.getResponses() == null) return;
        for (ApiResponse r : op.getResponses().values()) {
            Content content = r.getContent();
            if (content != null && content.containsKey("*/*")) {
                Content fixed = new Content();
                content.forEach((k, v) -> fixed.addMediaType("*/*".equals(k) ? JSON : k, v));
                r.setContent(fixed);
            }
        }
        if (path.endsWith("/errors.csv")) {
            ApiResponse ok = op.getResponses().get("200");
            if (ok != null) {
                ok.setDescription("The row errors of the job as a CSV attachment (`Content-Disposition: attachment; filename=\"<id>-errors.csv\"`).");
                ok.setContent(new Content().addMediaType("text/csv", new MediaType().schema(new StringSchema())));
            }
        }
    }

    private void responses(String path, String method, Surface surface, Operation op) {
        List<Integer> statuses;
        if (surface == Surface.INTERNAL) {
            statuses = adminStatuses(path, method, op);
        } else {
            statuses = V1StatusCatalog.statuses(method, path);
            if (statuses == null) return;   // OpenApiContractIT fails the build for an operation the catalog does not know
        }
        ApiResponses old = op.getResponses() == null ? new ApiResponses() : op.getResponses();
        ApiResponse base = old.get("200");
        ApiResponses out = new ApiResponses();
        for (int status : statuses.stream().sorted().toList()) {
            String key = Integer.toString(status);
            if (status < 300) {
                out.addApiResponse(key, success(status, base, path, method, surface, statuses));
            } else if (old.containsKey(key)) {
                out.addApiResponse(key, old.get(key));
            } else if (surface == Surface.HEALTH && base != null) {
                out.addApiResponse(key, new ApiResponse().description("Not ready: a dependency is down (same report shape, status DOWN).")
                        .content(base.getContent()));
            } else {
                out.addApiResponse(key, error(status, path, surface));
            }
        }
        op.setResponses(out);
    }

    private ApiResponse success(int status, ApiResponse base, String path, String method, Surface surface, List<Integer> statuses) {
        if (status == 200 && base != null) {
            if (statuses.contains(201) && surface == Surface.INTERNAL) {
                base.setDescription("OK: an update (the request carried `expectedVersion`), or an identical replay.");
            }
            return base;
        }
        ApiResponse r = new ApiResponse();
        String reason = REASON.get(status);
        if (status == 201 && statuses.contains(200)) {
            reason = "Created: the first write (no `expectedVersion` in the request)";
        } else if (status == 202) {
            reason = "Accepted: the work continues asynchronously";
        }
        r.setDescription(reason);
        if (status != 204 && base != null) {
            r.setContent(base.getContent());
            r.setHeaders(base.getHeaders());
        }
        return r;
    }

    private List<Integer> adminStatuses(String path, String method, Operation op) {
        List<Integer> out = new ArrayList<>();
        int[] ok = ADMIN_SUCCESS.get(V1StatusCatalogKey.of(method, path));
        if (ok == null) {
            out.add(200);
        } else {
            for (int s : ok) out.add(s);
        }
        boolean writes = !method.equals("GET");
        boolean body = op.getRequestBody() != null;
        out.add(400);
        out.add(401);
        out.add(403);
        if (path.contains("{") || (path.equals("/api/v1/products") && method.equals("GET"))
                || ADMIN_BODY_REFERENCED_404.containsKey(V1StatusCatalogKey.of(method, path))) out.add(404);
        if (writes) out.add(409);
        if (body) out.add(413);
        if (writes && body) out.add(422);
        if (ADMIN_503_PREFIXES.stream().anyMatch(path::startsWith)) out.add(503);
        out.add(500);
        return out;
    }

    private ApiResponse error(int status, String path, Surface surface) {
        ApiResponse r = new ApiResponse();
        if (surface == Surface.INTERNAL) {
            r.setDescription(adminDescription(status, path));
            r.setContent(json(ref("AdminErrorEnvelope")));
            if (status == 413) r.addHeaderObject("Connection", closeHeader());
            return r;
        }
        boolean legacy = path.startsWith("/catalog/v1");
        String family = legacy ? "PlatformErrorEnvelope" : v1Family(path);
        r.setDescription(v1Description(status, legacy));
        if (status == 413) {
            r.setContent(json(ref("PlatformErrorEnvelope")));
            r.addHeaderObject("Connection", closeHeader());
        } else if (status == 400 && !legacy) {
            Schema<?> either = new Schema<>();
            either.oneOf(List.<Schema>of(ref(family), ref("PlatformErrorEnvelope")));
            r.setContent(json(either));
        } else {
            r.setContent(json(ref(family)));
        }
        if (status == 429) {
            r.addHeaderObject("Retry-After", new Header().description("Seconds to wait before retrying.").schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
        }
        return r;
    }

    private static Header closeHeader() {
        return new Header().description("The connection is closed after a refused body.").schema(new StringSchema()._enum(List.of("close")));
    }

    private static String v1Family(String path) {
        if (path.startsWith("/v1/auth/otp")) return "V1OtpErrorEnvelope";
        if (path.startsWith("/v1/customer/checkout")) return "V1CheckoutErrorEnvelope";
        if (path.startsWith("/v1/auth/") || path.startsWith("/v1/customer/")) return "V1ErrorEnvelope";
        return "V1PublicReadErrorEnvelope";
    }

    private String adminDescription(int status, String path) {
        return switch (status) {
            case 400 -> "MALFORMED_REQUEST / MISSING_HEADER: unreadable body, undecodable or unsupported query parameter, bad value.";
            case 401 -> "UNAUTHENTICATED: no, invalid or expired bearer credential (one generic body for every cause).";
            case 403 -> "FORBIDDEN: the credential is not allowlisted, or its role may not perform this operation.";
            case 404 -> "NOT_FOUND (or a domain-specific not-found such as NODE_NOT_FOUND, IMPORT_JOB_NOT_FOUND).";
            case 409 -> "Conflict: STALE_VERSION (optimistic lock lost), STATE_CONFLICT, IDENTITY_COLLISION, DUPLICATE_*, IMPORT_JOB_STATE and other state conflicts.";
            case 413 -> "PAYLOAD_TOO_LARGE: the body exceeds " + (path.startsWith(HttpPlatformProperties.BULK_IMPORT_PREFIX)
                    ? kib(http.getBulkImportMaxRequestBodyBytes()) : isContentBlockWrite(path)
                    ? kib(http.getContentBlockMaxRequestBodyBytes()) : kib(http.getMaxRequestBodyBytes()))
                    + ". Refused before authentication, `Connection: close`.";
            case 422 -> "Valid JSON that the domain refuses: ATTRIBUTE_VIOLATION, EVIDENCE_GATE, BUNDLE_COMPONENT, IMMUTABLE_FIELD, VARIANT_PACK_INVALID, INVALID_PRICE, INVALID_INVENTORY, INVALID_MEDIA, INVALID_CONTENT, INVALID_IMPORT and similar.";
            case 500 -> "INTERNAL: unexpected failure; the body carries only the request id.";
            case 503 -> path.startsWith("/api/v1/admin/media") || path.startsWith("/api/v1/admin/content/uploads")
                    ? "MEDIA_STORAGE_NOT_CONFIGURED / MEDIA_STORAGE_UNAVAILABLE: the object store is not configured or not reachable."
                    : path.startsWith("/api/v1/admin/inventory") ? "LIST_TIMEOUT: the stock list exceeded its time bound; retry with a narrower filter."
                    : "SERVICE_UNAVAILABLE: the backing store is unavailable; retry later.";
            default -> "Error";
        };
    }

    private static String v1Description(int status, boolean legacy) {
        String envelope = legacy ? " Flat body with snake_case `request_id` (PlatformErrorEnvelope)." : "";
        return switch (status) {
            case 400 -> "INVALID_REQUEST (or a family code such as INVALID_CURSOR). A domain rejection uses the family envelope with `requestId`; "
                    + "an undecodable query string is refused earlier by a platform filter with `request_id` (PlatformErrorEnvelope)." + (legacy ? " Flat body with snake_case `request_id`." : "");
            case 401 -> "UNAUTHENTICATED: missing, invalid or expired access token.";
            case 404 -> "NOT_FOUND (a malformed id is indistinguishable from an unknown one)." + envelope;
            case 406 -> "NOT_ACCEPTABLE: the Accept header excludes application/json.";
            case 409 -> "Conflict; the family-specific codes are listed in docs/api/v1/openapi.yaml.";
            case 410 -> "QUOTE_EXPIRED: the checkout quote's validity window has passed.";
            case 412 -> "Precondition failed: If-Match does not match the current version.";
            case 413 -> "PAYLOAD_TOO_LARGE: the body exceeds the bound for the route. Refused before authentication, `Connection: close`. Flat body with snake_case `request_id`.";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE: the body must be application/json.";
            case 428 -> "Precondition required: the If-Match header is missing.";
            case 429 -> "RATE_LIMITED: retry after the number of seconds in Retry-After." + envelope;
            case 500 -> "INTERNAL: unexpected failure.";
            case 503 -> "SERVICE_UNAVAILABLE: a dependency is unavailable; retry later." + envelope;
            default -> "Error";
        };
    }

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref(SCHEMAS + name);
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType(JSON, new MediaType().schema(schema));
    }

    // ------------------------------------------------------------------ security

    private static void security(String path, Surface surface, Operation op) {
        List<SecurityRequirement> security = new ArrayList<>();
        switch (surface) {
            case INTERNAL -> security.add(new SecurityRequirement().addList(ADMIN_BEARER));
            case CUSTOMER_AUTHENTICATED -> security.add(new SecurityRequirement().addList(CUSTOMER_BEARER));
            case PUBLIC_CONSUMER -> {
                if (path.equals("/v1/auth/logout")) {
                    // classified public (no filter), but the controller verifies the customer bearer itself
                    security.add(new SecurityRequirement().addList(CUSTOMER_BEARER));
                } else if (!path.startsWith("/v1/auth/")) {
                    security.add(new SecurityRequirement());   // anonymous is allowed
                    security.add(new SecurityRequirement().addList(TRUSTED_CALLER_NAME).addList(TRUSTED_CALLER_SECRET));
                }
            }
            default -> { }
        }
        op.setSecurity(security);   // an empty list means "no authentication" explicitly
    }

    // ------------------------------------------------------------------ bounds

    /** The documented path templates of the two content-block writes (create, replace). */
    private static boolean isContentBlockWrite(String path) {
        return path.equals("/api/v1/admin/content/blocks") || path.equals("/api/v1/admin/content/blocks/{id}");
    }

    private void bodyBound(String path, String method, Operation op) {
        if (op.getRequestBody() == null || SurfaceClassifier.classify(path) != Surface.INTERNAL) return;
        boolean bulk = path.startsWith(HttpPlatformProperties.BULK_IMPORT_PREFIX);
        boolean blocks = !bulk && isContentBlockWrite(path) && !method.equals("GET");
        String note = "Request body bound: " + (bulk ? kib(http.getBulkImportMaxRequestBodyBytes()) + " (bulk import, up to 500 rows per file)"
                : blocks ? kib(http.getContentBlockMaxRequestBodyBytes()) + " on this route (a LEGAL block's `payload.body` is at most 60000 characters; "
                + "a character takes up to 3 bytes in UTF-8, and JSON escapes `\\n` and `\\\"` cost 2)"
                : kib(http.getMaxRequestBodyBytes())) + "; a larger body is refused with 413 PAYLOAD_TOO_LARGE before authentication.";
        op.setDescription(op.getDescription() == null || op.getDescription().isBlank() ? note : op.getDescription() + "\n\n" + note);
        if (path.equals("/api/v1/admin/imports/jobs/{id}/rows") && method.equals("POST")) {
            op.getRequestBody().getContent().addMediaType("text/csv", new MediaType().schema(new StringSchema()
                    .description("RFC 4180 CSV, UTF-8, header row first. Columns (case and punctuation insensitive): id, title, brand, gtin, "
                            + "market (default IN), internalKey, vertical, release, classification (default provisional) and any "
                            + "attr.<name> column. A malformed or over-limit file is refused as a whole (422 INVALID_IMPORT, or 413) and "
                            + "leaves the job unchanged."))
                    .example("id,title,brand,gtin,vertical,release\nTZP-RICE-1,Basmati Rice 5 kg,BR-1,8901234567890,TZV-000001,0.9.0"));
            op.setDescription(op.getDescription() + "\n\nThe same route also accepts `Content-Type: text/csv` (streamed, counted against "
                    + "the same bound; see docs/ops/BULK_IMPORT.md). OpenAPI allows one operation per method and path, so both request "
                    + "media types are listed here; the JSON form is `RowsRequest`. Only one upload per job runs at a time (a concurrent "
                    + "one is 409 IMPORT_JOB_STATE).");
        }
    }

    // ------------------------------------------------------------------ the shared admin product list / lookup

    private void adminProductListAndLookup(OpenAPI api) {
        PathItem item = api.getPaths().get("/api/v1/products");
        if (item == null || item.getGet() == null) return;
        Operation get = item.getGet();   // operationId stays productGetByCanonicalKey
        get.setSummary("List products, or look one up by canonical key");
        get.setDescription("""
                One route, two behaviours, selected by the query string (they are two handlers on `GET /api/v1/products`):

                * **Identity lookup**: when `canonicalKey` is present the call returns ONE `ProductResponse` (404 `NOT_FOUND` \
                when the key is unbound). Every other parameter is ignored in this mode.
                * **List**: without `canonicalKey` the call returns an `AdminProductListResponse`: ascending id order, keyset-paged \
                by `cursor` (the last id of the previous page), `limit` 1..200 (default 50). `lifecycle` and `status` require \
                `verticalId` (else 400). Unknown or repeated parameters, and empty or malformed values, are 400.""");
        List<Parameter> params = new ArrayList<>();
        params.add(query("canonicalKey", "Identity lookup key. When present, switches the response to a single product.", null));
        params.add(query("verticalId", "List filter: classification vertical id.", null));
        params.add(query("lifecycle", "List filter on product lifecycle; requires `verticalId`.", null));
        params.add(query("status", "List filter on classification status; requires `verticalId`.", null));
        params.add(query("cursor", "List paging: the `nextCursor` of the previous page (a product id).", null));
        params.add(new QueryParameter().name("limit").required(false).description("List page size, 1..200 (default 50).")
                .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(200))._default(50)));
        get.setParameters(params);
        ApiResponse ok = get.getResponses().get("200");
        if (ok != null) {
            ok.setDescription("A single `ProductResponse` when `canonicalKey` was supplied; otherwise an `AdminProductListResponse` page.");
            Schema<?> either = new Schema<>();
            either.oneOf(List.<Schema>of(ref("ProductResponse"), ref("AdminProductListResponse")));
            ok.setContent(json(either));
        }
    }

    /** Admin list routes read their query by hand ({@code HttpServletRequest}), so springdoc saw no parameters at all. */
    private void declareListParameters(OpenAPI api) {
        PathItem nodes = api.getPaths().get("/api/v1/taxonomy/nodes");
        if (nodes != null && nodes.getGet() != null && nodes.getGet().getParameters() == null) {
            nodes.getGet().setParameters(new ArrayList<>(List.of(
                    query("parentId", "Filter: parent node id.", null),
                    query("nodeType", "Filter: node type.", null),
                    query("status", "Filter: node status.", null),
                    query("cursor", "Paging: the `nextCursor` of the previous page (a node id).", null),
                    new QueryParameter().name("limit").required(false).description("Page size, 1..200 (default 50).")
                            .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE).maximum(java.math.BigDecimal.valueOf(200))._default(50)))));
        }
        PathItem stock = api.getPaths().get("/api/v1/admin/inventory");
        if (stock != null && stock.getGet() != null && stock.getGet().getParameters() == null) {
            stock.getGet().setParameters(new ArrayList<>(List.of(
                    query("location", "Filter: fulfillment location id.", null),
                    new QueryParameter().name("state").required(false).description("Filter: stock state.")
                            .schema(new StringSchema()._enum(List.of("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "INACTIVE"))),
                    new QueryParameter().name("limit").required(false).description("Page size.")
                            .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)),
                    query("cursor", "Paging: the opaque `nextCursor` of the previous page. Keep following it until it is null: a page may be short or empty.", null))));
        }
    }

    private static Parameter query(String name, String description, String pattern) {
        StringSchema schema = new StringSchema();
        if (pattern != null) schema.pattern(pattern);
        return new QueryParameter().name(name).required(false).description(description).schema(schema);
    }

    // ------------------------------------------------------------------ required (primitives, responses only)

    private void requiredPrimitives(OpenAPI api, Components components) {
        Map<String, Schema> schemas = components.getSchemas();
        if (schemas == null) return;
        Set<String> inRequests = new HashSet<>();
        if (api.getPaths() != null) {
            for (PathItem item : api.getPaths().values()) {
                for (Operation op : item.readOperations()) {
                    if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                        op.getRequestBody().getContent().values().forEach(m -> collect(m.getSchema(), schemas, inRequests));
                    }
                }
            }
        }
        Set<String> colliding = guard.collisions().keySet();
        for (Map.Entry<String, Set<String>> e : guard.primitiveProperties().entrySet()) {
            Schema<?> schema = schemas.get(e.getKey());
            if (schema == null || inRequests.contains(e.getKey()) || colliding.contains(e.getKey()) || schema.getProperties() == null) continue;
            for (String property : e.getValue()) {
                if (schema.getProperties().containsKey(property) && (schema.getRequired() == null || !schema.getRequired().contains(property))) {
                    schema.addRequiredItem(property);
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collect(Schema schema, Map<String, Schema> all, Set<String> seen) {
        if (schema == null) return;
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (seen.add(name)) collect(all.get(name), all, seen);
            return;
        }
        if (schema.getProperties() != null) ((Map<String, Schema>) schema.getProperties()).values().forEach(s -> collect(s, all, seen));
        collect(schema.getItems(), all, seen);
        if (schema.getAdditionalProperties() instanceof Schema s) collect(s, all, seen);
        for (List<Schema> group : new List[]{schema.getAllOf(), schema.getOneOf(), schema.getAnyOf()}) {
            if (group != null) group.forEach(s -> collect(s, all, seen));
        }
    }

    /** The untyped body parameters are now documented by DocumentedRequestBodies; drop the placeholder schema when nothing uses it. */
    private static void dropUnusedJsonNode(OpenAPI api, Components components) {
        if (components.getSchemas() == null || !components.getSchemas().containsKey("JsonNode")) return;
        String rendered = Json.pretty(api);
        if (!rendered.contains("\"" + SCHEMAS + "JsonNode\"")) components.getSchemas().remove("JsonNode");
    }

    /** Key shape shared by {@link #ADMIN_SUCCESS}: method plus path with every variable normalised to {@code {}}. */
    private static final class V1StatusCatalogKey {
        static String of(String method, String path) {
            return method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]*}", "{}");
        }
    }
}

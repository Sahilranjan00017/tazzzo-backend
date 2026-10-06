package com.tazzzo.catalog.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Transport records only. DTOs carry NO business logic and NO defaults that could become a
 * second interpretation of catalogue rules — every field maps straight onto an existing
 * service parameter.
 */
public final class ApiDtos {

    private ApiDtos() { }

    // ---- admin bootstrap (GET /api/v1/admin/me)

    /**
     * The authenticated admin, for CMS bootstrap. {@code email} is the allowlist's display label of a HUMAN_ADMIN and is
     * ABSENT (not null) for a service account; {@code roles} are sorted. Deliberately no credential id, raw subject, token
     * or claim data.
     */
    public record AdminMeResponse(String actorType, String actorId,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String email, List<String> roles) { }

    /**
     * One audit event (ALLOWLISTED projection, field order fixed). Never a token, header, session, claim, email, stack or
     * the ledger's free-form detail. {@code credentialId} is the persisted non-secret label or null (never manufactured);
     * {@code requestId} is null only for SYSTEM actors. {@code occurredAt} is a UTC ISO-8601 instant.
     */
    public record AuditEventDto(String id, String occurredAt, String action, String targetType, String targetId,
                                String actorType, String actorId, String credentialId, String requestId) { }

    /** Newest first; {@code nextCursor} is an opaque token, null on the last page. */
    public record AuditEventsResponse(List<AuditEventDto> items, String nextCursor) { }

    // ---- products
    public record GtinDto(String value, String market) { }

    public record BundleComponentDto(String componentProductId, int qty, String verticalIdSnapshot) { }

    public record PackOfDto(String componentProductId, int qty) { }

    public record CreateProductRequest(String id, String productType, String identityType,
                                       String internalKey, List<GtinDto> gtins, String brandCode,
                                       String title, String verticalId, String releaseId,
                                       String classificationStatus, Map<String, Object> attributes,
                                       List<String> evidenceRefs,
                                       List<BundleComponentDto> bundleContents,
                                       PackOfDto packOf) { }

    public record PatchProductRequest(String title) { }

    public record RetireRequest(String reason) { }

    public record ReviveRequest(Integer formulationVersion) { }

    // ---- evidence (approved Evidence API contract)
    public record PayloadRefDto(String store, String objectId, String sha256) { }

    /** `payload` is declared ONLY so raw bytes can be explicitly refused (PAYLOAD_NOT_ACCEPTED). */
    public record CreateEvidenceRequest(String id, String evidenceType, String source,
                                        String sourceVersion, PayloadRefDto payloadRef,
                                        String excerpt, String url, String observedAt,
                                        String payload) { }

    public record RetractEvidenceRequest(String to, String reason) { }

    public record EvidenceResponse(String id, String evidenceType, String source,
                                   String sourceVersion, PayloadRefDto payloadRef,
                                   String excerpt, String url, String validity,
                                   String payloadState, String observedAt) { }

    public record RetractAcceptedResponse(String id, String validity, String cascade) { }

    public record ClassifyRequest(String verticalId, String releaseId, String status,
                                 Double confidence, List<String> evidenceRefs) { }

    public record PublishClaimRequest(String attributeKey, List<String> evidenceRefs) { }

    public record GtinBindRequest(String gtin, String market) { }

    // ---- taxonomy
    public record OpenReleaseRequest(String releaseId, String basedOn) { }

    public record RenameNodeRequest(String name, Integer expectedVersion) { }

    public record MoveNodeRequest(String newParentId, Integer expectedVersion) { }

    public record MergeNodeRequest(String survivorId, Integer expectedVersion,
                                   Boolean schemaReconciliationApproved) { }

    public record SplitNodeRequest(List<String> childNames, Integer expectedVersion) { }

    // ---- attributes
    public record CreateAttributeRequest(String key, String type, String governance,
                                        List<String> knownValues) { }

    public record AddEnumValueRequest(String value) { }

    public record AddSchemaFieldRequest(String key, Boolean required, Boolean allowBreaking) { }

    // ---- responses
    public record ProductResponse(String id, String productType, String lifecycle, String brandCode,
                                  String title, Map<String, Object> classification,
                                  Map<String, Object> attributes, int version, String taxonomyPath) { }

    public record IdResponse(String id, Integer version) { }

    public record CreateNodeRequest(String nodeType, String name, String parentId, String attributeSchemaId) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeListResponse(List<NodeResponse> items, String nextCursor) { }

    public record ProductSummary(String id, String productType, String lifecycle, String brandCode, String title,
                                 String verticalId, String classificationStatus, int version) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListResponse(List<ProductSummary> items, String nextCursor) { }

    public record NodeResponse(String id, String nodeType, String name, String parentId,
                               String status, String attributeSchemaId, Integer version) { }

    public record PathResponse(String verticalId, String path, List<NodeResponse> nodes) { }

    public record AcceptedResponse(String status, String detail) { }

    public record ReleaseResponse(String id, String status, String basedOn) { }

    public record AttributeResponse(String key, int version, String type, String governance,
                                    List<String> knownValues, String status) { }

    public record SchemaFieldResponse(String key, boolean required) { }

    public record SchemaResponse(String schemaId, int version, String scope,
                                 List<SchemaFieldResponse> fields, String status) { }
}

package com.tazzzo.catalog.api.docs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DOCUMENTATION-ONLY request shapes for the handlers whose body parameter is a raw {@code JsonNode} (they validate
 * the body by hand, so the OpenAPI generator saw an untyped object). Nothing here is ever bound, deserialised or
 * returned at runtime: the handlers keep their {@code JsonNode} parameter and their exact validation, and these
 * records are only referenced from {@code @io.swagger.v3.oas.annotations.parameters.RequestBody}. They mirror the
 * hand-written parsing in each handler (field names, types, required-ness, unknown-field policy).
 */
public final class DocumentedRequestBodies {

    private DocumentedRequestBodies() {
    }

    /** {@code POST /api/v1/admin/orders/{orderId}/transition}; unknown fields are rejected (400). */
    @Schema(name = "StaffOrderTransitionRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record StaffOrderTransition(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED"},
                    description = "Target status.") String to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int64", description = "Optimistic-lock version the staff member last saw.")
            long expectedVersion,
            @Schema(description = "Staff reason; meaningful for CANCELLED only.") String reason) { }

    /** {@code POST /api/v1/admin/support/cases/{caseId}/messages} and {@code POST /v1/customer/support/cases/{caseId}/messages}. */
    @Schema(name = "SupportReplyRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SupportReply(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000, description = "Message text (trimmed; at most 2000 characters).")
            String message) { }

    /** {@code POST /api/v1/admin/support/cases/{caseId}/assign}: assigns the case to the calling staff member. */
    @Schema(name = "StaffCaseAssignRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record StaffCaseAssign(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int64", minimum = "1",
                    description = "Optimistic-lock version of the case.") long expectedVersion) { }

    /** {@code POST /api/v1/admin/support/cases/{caseId}/status}. */
    @Schema(name = "StaffCaseStatusRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record StaffCaseStatus(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"},
                    description = "Target status.") String to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int64", minimum = "1",
                    description = "Optimistic-lock version of the case.") long expectedVersion) { }

    /** {@code POST /v1/customer/support/cases}; unknown fields are rejected (400). */
    @Schema(name = "CustomerSupportOpenRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CustomerSupportOpen(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ORDER_ISSUE", "DELIVERY", "PRODUCT", "ACCOUNT", "OTHER"})
            String category,
            @Schema(description = "Optional: the order the case is about; must be one of the caller's own orders.") String orderId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120) String subject,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000) String message) { }

    /** {@code PUT /v1/customer/cart/items/{skuId}}: only {@code quantity} is read, other fields are ignored. */
    @Schema(name = "CartSetItemRequest")
    public record CartSetItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32",
                    description = "Absolute quantity to set (a JSON integer that fits in 32 bits; the domain bounds it).") int quantity) { }

    /** {@code POST /v1/customer/checkout/quote}: only {@code addressId} is read. */
    @Schema(name = "CheckoutQuoteRequest")
    public record CheckoutQuote(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One of the caller's own saved addresses.") String addressId) { }

    /** {@code POST /v1/customer/orders}. */
    @Schema(name = "PlaceOrderRequest")
    public record PlaceOrder(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The checkout quote being placed (replay of the same quote returns the same order).")
            String quoteId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"COD"}, description = "Only COD exists.")
            String paymentMethod,
            @Schema(description = "Optional delivery slot id from GET /v1/customer/delivery/slots; required when the deployment enforces slots.")
            String deliverySlotId) { }

    /** {@code POST /v1/customer/orders/{orderId}/cancel}. */
    @Schema(name = "CancelOrderRequest")
    public record CancelOrder(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CHANGED_MIND", "ORDERED_BY_MISTAKE", "OTHER"})
            String reason) { }

    /** {@code PATCH /v1/customer/profile}: merge-patch semantics. At least one field must be present; an explicit null clears it. */
    @Schema(name = "CustomerProfilePatchRequest")
    public record CustomerProfilePatch(
            @Schema(nullable = true) String displayName,
            @Schema(nullable = true) String email) { }

    /** {@code PATCH /v1/customer/addresses/{addressId}}: merge-patch semantics; an absent key is unchanged, an explicit null clears an optional field. */
    @Schema(name = "AddressPatchRequest")
    public record AddressPatch(
            @Schema(nullable = true) String label,
            @Schema(nullable = true) String recipientName,
            @Schema(nullable = true) String recipientPhone,
            @Schema(nullable = true) String addressLine1,
            @Schema(nullable = true) String addressLine2,
            @Schema(nullable = true) String landmark,
            @Schema(nullable = true) String city,
            @Schema(nullable = true) String state,
            @Schema(nullable = true) String postalCode,
            @Schema(nullable = true, format = "double") Double latitude,
            @Schema(nullable = true, format = "double") Double longitude) { }

    /** {@code POST /v1/customer/account/deletion}. */
    @Schema(name = "AccountDeletionRequest")
    public record AccountDeletion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DELETE"}, description = "Literal confirmation.")
            String confirm) { }
}

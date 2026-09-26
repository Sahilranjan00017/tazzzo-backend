# Architecture Debt Register

Tracked, gated debt only. An entry may be closed **only** by the PR named in its gate, and the
gate is part of that PR's review checklist. Nothing here is "known and accepted forever" —
every entry has an owner PR and a hard deadline expressed in PR order.

| ID | Raised | Gate (MUST FIX BEFORE) | Status |
|----|--------|------------------------|--------|
| OPENAPI-INTERNAL-PROJECTION-DEBT | PR-08 final review (2026-09-26) | **PR-10** (public API exposure) | OPEN |

---

## OPENAPI-INTERNAL-PROJECTION-DEBT

**What:** the frozen `docs/api/v1/openapi.yaml` embeds an **internal** schema,
`components.schemas.ProductCardBaseProjection` (self-described as "INTERNAL persisted
projection — NOT a public API response … documented here for architecture reference only"),
and that documented shape has **drifted** from the implemented internal models:

| openapi.yaml claims | implementation (PR-07/PR-08) |
|---|---|
| `thumbnailUrl` stored in the projection | projection stores `primaryAssetKey`; the URL is resolved at runtime by `MediaUrlResolver` (ratified assetKey-vs-URL decision, PR-05/07) |
| `discountPercent` / `discountAmountPaise` stored | discount is **computed at enrichment time** (PR-08), never persisted |
| `name`, `categoryNodeId`, `releaseId`, `mediaRefId`, `packSize`, `unit`, `offerSummary`, `badges`, `brandName`, `sourceVersions{}`, `freshness.stalePrice` | projection fields are `title`, `brandCode`, `verticalId`, `priceStatus`, `currency`, and flat `catalogVersion`/`priceVersion`/`mediaVersion`/`projectionVersion`; the speculative fields do not exist |

**Why not fixed in PR-08 (Option B, chosen):** the `/v1` contract file is **byte-frozen** and
PR-08 is an internal-only PR — editing the frozen file inside an internal PR would break the
"public contract changes are their own reviewable event" rule for a documentation-only gain.
The public `ProductCard` schema (what clients actually consume) is **not** affected by this
drift; only the courtesy-documented internal shape is stale.

**Gate — PR-10 MUST, before exposing any `/v1` endpoint:**

1. Remove `ProductCardBaseProjection` from `openapi.yaml` (internal shapes do not belong in the
   public contract file) **or** replace it with the real implemented shape, explicitly marked
   internal — removal is the recommended option.
2. Re-verify the public `ProductCard` ↔ `RuntimeProductCard` mapping field-by-field; fields with
   no authoritative source yet (`brandName`, `packSize`, `unit`, `offerSummary`, `badges`,
   `rating`, `ratingCount`, `etaMinutesMin/Max`) are emitted as `null` (and `sponsored=false`)
   by the PR-10 mapper — never fabricated.
3. This entry flips to CLOSED in the same PR, with the closing commit referenced here.

**Not in scope of this entry:** the freshness-gate debt (product_card_base NOT LIVE until an
explicit freshness mechanism) is tracked by ADR-003.1/PR-07 notes, not here.

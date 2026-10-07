# TAZZZO — Platform gap matrix (all repositories)

Verified 2026-10-07 against backend `main` `7d491dd`, web `cms/16-qa-security` `1a05e04`, app `cc500db`, infra `bfd335e`. Status vocabulary: NOT_STARTED · PARTIAL · IMPLEMENTED_UNMERGED · MERGED_UNVERIFIED · INTEGRATED_VERIFIED · STAGING_VERIFIED · LIVE_VERIFIED · BLOCKED_EXTERNAL. "Integrated" means proven against a real backend; nothing in the platform is beyond MERGED_UNVERIFIED on a live stack today.

## Backend (catalog-service, modular monolith)

| Area | Exists (evidence) | Missing | Status |
|---|---|---|---|
| Product data model | `products` (identity, GTINs, classification, attributes, bundle/variant refs), `price_current`+`price_events`, `inventory` (sku, location), media sets, `product_card_base` projection, taxonomy releases, `canonical_keys`, `gtin_registry` — separate collections, CAS versions | Brand entity (collection exists, no reader/writer), variant groups (unused), supplier/sourcing, pack size/unit, tax (HSN/GST/FSSAI), descriptions/ingredients/nutrition/allergens, SEO, verification status, channel field | PARTIAL |
| Hard-coded catalogue limits | none for catalogue size; page sizes 50/200; import 500 rows/request | — | OK |
| Bulk import | `POST /api/v1/admin/imports/{products,prices,inventory}` JSON 1–500 rows, whole-file validate, dry run, audit event, resubmit-safe (`docs/ops/BULK_IMPORT.md`); **PR #100 (open):** product import jobs — streamed CSV/JSON of any size, checkpointed validate/apply, pause/resume, progress, per-row verdicts + error file, history, DB-enforced per-job duplicates | price/stock job kinds, XLSX, purge policy, dead-letter, delta/media/supplier imports, CMS screen for jobs | PARTIAL → mostly covered once #100 merges |
| Admin product list | keyset cursor, limit ≤200, filters verticalId/lifecycle/status (`ProductController`, `AdminListParams`) | text/brand search, sort, totals, previous page, catalogue-wide lifecycle filter, bulk lifecycle, export | PARTIAL |
| Media | model + `MediaUrlResolver` + signed-upload API + ingest verifier + sniffer (`media/*`); **PR #95**: `S3MediaStorage` (presigned PUT binding key/type/size, inspection, outage → 503, owner-bound keys) | live bucket + CDN (B3), variants/thumbnails, dedup, cleanup, bulk mapping, cost tracking | IMPLEMENTED_UNMERGED (#95) / BLOCKED_EXTERNAL (live) |
| Search | prefix-token `$all` over `product_card_base.search_tokens`, keyset paging, ≤5 tokens | ranking, synonyms, typo tolerance, facets, suggestions, brand/attribute/price/availability filters | PARTIAL |
| Projection freshness | queue drain 200/15 s, reconcile 500/5 min | reconcile rate scales with catalogue: 100k ≈ 3.5 days, 1M ≈ 5 weeks per drift pass | PARTIAL |
| Pricing | immediate-only (ratified Option A), CAS, append-only history | scheduled/effective-dated prices, price list endpoint | PARTIAL |
| Inventory | (sku, location) atomic reserve/release, bulk via import | inventory list endpoint, low-stock feed, service-area availability projection | PARTIAL |
| Content | `content_blocks` BANNER/PRODUCT_RAIL/CATEGORY_GRID/FAQ, status+window, `live()`; **PR #96**: `audience` + `?channel=app\|web` (D1–D3) | banner media owner type, desktop/mobile variants (D4), click ids, cache invalidation, publication history | IMPLEMENTED_UNMERGED (#96) / PARTIAL |
| Error handling | PR #92 merged; PR #93 reviewed PASS | `/health/*` non-JSON Accept → 400 (pre-existing) | MERGED_UNVERIFIED (#92) / IMPLEMENTED_UNMERGED (#93) |
| Platform | Boot 3.3.13 (unsupported line), patched libs | supported Boot line (4.x migration; Dependabot #83 does not compile) | BLOCKED (planned) |
| Load/capacity tests | `CatalogCapacityIT` (branch `feature/catalogue-capacity-harness`), 5,000-SKU results recorded | 25k/100k results, concurrency, staging-sized environment | PARTIAL |

## CMS (tazzzo-web `apps/admin`) — detail in `TAZZZO_CMS_MASTER_GAP_MATRIX.md`
All business modules are IMPLEMENTED_UNMERGED on the #5→#19 stack, tested only against a fake backend; no Dockerfile; no customer website (`apps/web` absent).

## Mobile app (tazzzo-app, KMP)

| Area | Exists | Missing | Status |
|---|---|---|---|
| Catalogue browse/PDP/serviceability | `/v1/categories`, children, products (cursor 20/50), `/v1/products/{id}`, `/v1/serviceability` | search (capability off), suggestions | MERGED_UNVERIFIED |
| Images | custom loader, 48 MB LRU, ≤1024 px decode, https only, lazy in grids | disk cache, responsive variants, failure fallback asset policy | PARTIAL |
| Home content | static drawables for hero/quality/bulk plates; first page of first root as rail | `/v1/content/home` banners, CMS rails/grids, category images from backend | NOT_STARTED |
| Channel | none | no channel header; relies on backend filtering | NOT_STARTED |
| Cart/checkout/COD/orders/profile/addresses | present (per app docs; not re-verified here) | E2E against staging | MERGED_UNVERIFIED |

## Infrastructure (tazzzo-infrastructure, Terraform, ap-south-1, staging only)

| Area | Exists | Missing | Status |
|---|---|---|---|
| Foundation | VPC (public subnets only), 3 SGs, ECS cluster (no services), 2 ECR, logs, IAM, SSM namespace, $20 budget | ALB, NAT, ECS services/task defs, Valkey, Atlas (not in TF), DNS, TLS | PARTIAL (apply state unverified) |
| Media/CDN/queues | none (only TF state bucket) | S3 media bucket, CloudFront `cdn.tazzzo.com`, SQS/job queue, image processing | NOT_STARTED / BLOCKED_EXTERNAL (budget approval) |
| Capacity assumptions | none | SKU/storage/traffic sizing | NOT_STARTED |

## Data
- **No 5,000-SKU master dataset exists in any repository.** Only 50-SKU validation CSVs (`tazzzo-research`). Launch validation is BLOCKED_EXTERNAL.

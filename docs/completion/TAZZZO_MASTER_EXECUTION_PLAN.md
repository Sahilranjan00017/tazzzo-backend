# TAZZZO — Master execution plan

Governs the completion program across tazzzo-backend, tazzzo-web, tazzzo-app and tazzzo-infrastructure. Milestone loop per PR: inspect live state → tests → implement → local validation → full regression → security check → focused PR → exact-head CI → independent adversarial review → resolve findings → **CEO approval to merge** → verify merged main → update `TAZZZO_MASTER_STATUS.md`.

Hard rules: no `--admin`, no force push, no auto-merge, no production deploy, no paid cloud resources without approval, one agent per file set, strict file ownership per PR.

## Workstreams and sequencing (dependency-aware; see `TAZZZO_DEPENDENCY_GRAPH.md`)

| # | Workstream | Repo | Scope | Depends on | Approval gate |
|---|---|---|---|---|---|
| P0 | BACKEND-COMPLETION: PR #93 review | backend | done (PASS) | — | merge approval |
| P1 | Audit + trackers | backend docs | this document set | — | none |
| P2 | CMS stack integration | web | merge #5→#19 in order; Dockerfile; run against real backend | owner approval | merge approval |
| P3 | MEDIA-STORAGE | backend (+infra) | S3-compatible `MediaStorage` (presigned PUT, HeadObject, ranged head bytes), MinIO Testcontainers ITs, config by env; then Terraform S3 bucket + CloudFront `cdn.tazzzo.com` (CDN-HOST-1 rule: do not repoint clients until operational) | — (local/test) | paid S3/CDN |
| P4 | MEDIA-API + MEDIA-CMS | backend, web | variants/thumbnails, dedup (content hash), replace/archive/safe delete, usage refs, bulk mapping; CMS upload via BFF-brokered signed URL (CSP) | P3 | none |
| P5 | CHANNEL-PUBLISHING + BANNER-CMS | backend, web | `content_blocks.channel` (APP/WEBSITE/BOTH) + migration + index, `?channel=` filter on public content, banner media owner type, desktop/mobile variants, click ids, publication history; CMS banner/rail/grid editors | P3 (banner images) | channel authorisation (web PR #7) |
| P6 | APP-INTEGRATION | app | `/v1/content/home` banners/rails, category images, image fallback/disk cache, deep links; verify Android and iOS separately | P5 | none |
| P7 | WEBSITE-CX | web (`apps/web`) | storefront on shared `/v1` APIs; customer auth separate from CMS | P5, design decision | hosting |
| P8 | CATALOGUE-SCALE + BULK-IMPORT | backend, web | capacity harness (TEST DATA) → async `import_jobs` (streamed CSV/XLSX, checkpoints, resume, history, delta) → brand/pack/tax/supplier entities + migrations → admin list search/sort/bulk | P1 | none |
| P9 | SEARCH-SCALE + pricing/inventory scale | backend | evaluate Atlas Search vs OpenSearch; ranking, facets, suggestions, sync; scheduled prices; inventory/low-stock lists; reconciler scaling | P8 harness | paid search infra if chosen |
| P10 | CMS operational modules | web | remaining PARTIAL rows of the CMS matrix | P2 | none |
| P11 | SECURITY-HARDENING + platform | backend | Boot supported-line migration (4.x), `/health` Accept exemption, commons-lang3 ≥3.18, static mappings off | P2 | none |
| P12 | FINAL-E2E-QA + performance | all | TEST 1–20 journeys; 5k/25k/100k benchmarks with pre-set thresholds | P3–P9 | staging |
| P13 | INFRA-READINESS | infra | ALB (desync mode), ECS services, Valkey, Atlas, DNS/TLS, monitoring, backups | approvals | paid |
| P14–15 | Release audit → production gate | all | | all | CEO |

## Performance targets (to be ratified before benchmarking — see `TAZZZO_PERFORMANCE_RESULTS.md`)
Proposed: product list p95 < 300 ms, PDP p95 < 300 ms, search p95 < 500 ms at 100k SKUs on a 1 vCPU/2 GB container against Atlas M10-class; import throughput ≥ 200 rows/s sustained with < 512 MB heap growth; projection drift pass < 24 h at 100k.

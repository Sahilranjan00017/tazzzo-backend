# Tazzzo media & CMS content — completion matrix

Single source of truth for the media / CMS-content workstream (CMS → backend → object storage/CDN → Android, iOS,
website). Statuses are evidence-based; "code exists" is never "done".

Allowed statuses: `NOT_STARTED` · `PARTIAL` · `IMPLEMENTED_UNMERGED` · `MERGED_UNVERIFIED` · `INTEGRATED_VERIFIED` ·
`STAGING_VERIFIED` · `LIVE_VERIFIED` · `BLOCKED_EXTERNAL`.

Last audit: 2026-10-09 (live GitHub state; backend `main` = `047ed12`; web `main` = `374333b`).

## 1. Existing PRs (Phase 0 audit)

| PR | Repo | Branch → base | Head | CI | Independent review | Merge |
|---|---|---|---|---|---|---|
| #95 S3-compatible media storage | backend | `feature/media-storage-s3` → `main` | `92190c3` | 4/4 green; main CI green after merge | PASS_WITH_FOLLOWUPS → remediated; re-review PASS_WITH_FOLLOWUPS | **MERGED** squash `70021ce` | unmerged, needs CEO approval |
| #96 APP/WEB/BOTH targeting | backend | `feature/content-channel-audience` → `main` | `adb80af` | 4/4 green; main CI green after merge | PASS_WITH_FOLLOWUPS → remediated; re-review PASS_WITH_FOLLOWUPS | **MERGED** squash `afa1a9a` | unmerged, needs CEO approval |
| #16 CMS media | web | `cms/07-media` → `cms/14-audit-status` (stack #5→#19) | `c3ab2cd` | 7/7 green | PASS_WITH_FOLLOWUPS — followed up by #21 | **MERGED** (merge commit, CMS stack #5–#22 all merged; web main `3be81e0`, CI 7/7) |
| #24 App home content | app | `feature/app-home-content` → `main` | `79173b6` (+ canonical product-id grammar) | 4/4 green; 1098/1098 Android + iOS | final verification READY_TO_MERGE_AFTER_BACKEND_DEPLOY | unmerged — **waits for a deployed backend with channel targeting** |
| #102 banner model / reorder / preview (new) | backend | `feature/content-banner-model` → `feature/content-channel-audience` | `cd07e50` (as PR #106; #102 was auto-closed when its base branch was deleted) | 4/4 green (one unrelated session-test flake, green on rerun) | PASS_WITH_FOLLOWUPS → remediated; carries the 503 fix from #105 | **MERGED** squash `5191eb7` |
| web #20 customer storefront (new) | web | `web/01-storefront` → `main` | `fd00305` | 9/9 green | PASS_WITH_FOLLOWUPS → remediated | **MERGED** squash `0b8d629` |
| infra #3 media S3 + CloudFront | infrastructure | `feature/infra-2-media` → `feature/infra-1-staging-foundation` | `b716efd` | fmt + validate both stacks (no CI in repo) | PASS_WITH_FOLLOWUPS → remediated | unmerged; gated off; needs spend approval |
| web #21 CMS media upload | web | `cms/20-media-upload` → `cms/16-qa-security` | `29d13cd` | 7/7 green | PASS_WITH_FOLLOWUPS → remediated | **MERGED** `07fcc7f` |
| web #22 CMS home content | web | `cms/21-home-content` → `cms/20-media-upload` | `ef88d23` | 7/7 green | PASS_WITH_FOLLOWUPS → remediated | **MERGED** `3be81e0` (web main) |
| backend #105 integration preview (draft) | backend | `integration/media-content` → `main` | `198fc07` | 4/4 green: full suite on #95+#96+#102 combined | n/a | **CLOSED without merge** (evidence only) | carries the banner-outage→503 fix into #102 after #95 merges |
| backend #104 flaky checkout snapshot tests (new) | backend | `fix/checkout-money-snapshot-flaky` → `main` | see PR | — | test-only | unmerged |
| web #23 local E2E harness (draft) | web | `e2e/media-content-local` → `main` | `f67ff6d` | **rerun 2026-10-09 on the MERGED heads** (backend `5191eb7`, web `3be81e0`, storefront `fd00305`, app `c5caed0`): 11/11 journeys, 4.7 min, fresh stack | `e2e/local-stack/EVIDENCE_2026-10-09.md` (+ 2026-10-08) | harness only; keep as draft |
| backend #108 trusted storefront caller identity | backend | `feature/storefront-caller-identity` → `main` | `09169dd` | 4/4 green (full suite); main CI green after merge | security review PASS_WITH_FOLLOWUPS → rotation (two active secrets) added | **MERGED** squash `c65e434` |
| backend #109 `GET /v1/categories/{id}` | backend | `feature/category-by-id` → `main` | `e5b5d45` (= reviewed `417d4e4` + main; net diff identical) | 4/4 green; main CI green after merge | PASS_WITH_FOLLOWUPS (low only) | **MERGED** squash `7c549f7` |
| backend #110 product-id grammar alignment (cart + OpenAPI) | backend | `fix/product-id-grammar-alignment` → `main` | `ead5a19` (= reviewed `50ee395` + main; net diff identical) | 4/4 green | PASS_WITH_FOLLOWUPS (1 medium = decision 5.2b) | **MERGED** squash `047ed12` |
| web #26 storefront per-IP limit, caller header, placeholder fix | web | `web/02-storefront-ratelimit` → `web/01-storefront` | `330696e` (= reviewed `b7ed1a3` tree, byte-identical, after #20 squash) | 9/9 green; web main 9/9 after merge | security review PASS_WITH_FOLLOWUPS → fail-closed + prefetch policy test | **MERGED** squash `374333b` |
| #1, #2 staging infra | infrastructure | `infra-0` → `main`, `infra-1` → `infra-0` | `bfd335e` | none | not reviewed in this workstream | unmerged, not applied |

Overlaps: #95 and #96 both insert at the top of `docs/ENGINEERING_STATUS.md` "In review" — the second to merge needs a
trivial rebase; no code overlap. Later CMS stack PRs (#17–#19) do not touch media files.

## 2. Dependency graph

```
backend #95 (storage) ──┬──> MEDIA-CMS (real upload in CMS; extends web #16)
                        └──> CONTENT-BE-2 (banner media fields)
backend #96 (channel) ──┬──> CONTENT-BE-2 ──> CONTENT-CMS (banners/rails/grids UI)
                        ├──> app #24 (sends ?channel=app; main answers 400 to any query → hard ordering)
                        └──> CONTENT-WEB (new customer storefront, ?channel=web)
backend ImageRole (PRIMARY, GALLERY only) ──> extra roles (packaging/nutrition/…) need a backend + app contract change
infra #1 → #2 → MEDIA-INFRA (S3 bucket + CloudFront)  [paid resources: CEO approval]
everything ──> E2E-MEDIA (cross-channel verification) ──> staging ──> CEO release gate
```

## 3. Feature matrix

| # | Feature | Repo | Branch / PR | Status | Evidence | External blocker | Next step |
|---|---|---|---|---|---|---|---|
| 1 | Media upload init (presigned PUT) | backend | #95 | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED) | S3MediaStorageIT, MediaUploadEndToEndIT (S3Mock), S3SignatureEnforcementIT (Versity S3 Gateway, SigV4 + preconditions) | — | CEO merge approval |
| 2 | Magic-byte / MIME / size verification at reference time | backend | main | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED) | MediaIngestUnitTest, E2E IT refusals | — | dimension/decode check not implemented (follow-up) |
| 3 | Write-once uploads (no overwrite of verified bytes) | backend | #95 | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED) | S3SignatureEnforcementIT: re-PUT → 412 | — | push + re-review |
| 4 | Product media set metadata (roles, alt, order, primary) | backend | main | MERGED_UNVERIFIED | existing ITs; roles limited to PRIMARY/GALLERY | — | role expansion decision (see §5) |
| 5 | Public delivery URL / CDN base URL | backend | main (`MediaUrlResolver`) | PARTIAL | unit tests only; no CDN exists | CDN not provisioned | MEDIA-INFRA |
| 6 | Cache-Control / versioned URLs / invalidation | backend + infra | — | NOT_STARTED | keys are unique per upload (implicitly versioned) | CDN | define in MEDIA-INFRA |
| 7 | Unreferenced-object reaper / lifecycle | backend + infra | — | NOT_STARTED | — | bucket | follow-up PR |
| 8 | Channel targeting APP/WEB/BOTH (backend) | backend | #96 | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED) | ContentChannelTargetingIT (real HTTP + Mongo) 5→7 tests | — | push remediation; CEO merge approval |
| 9 | Banner model: subtitle, desktop image, alt, createdBy/updatedBy, derived scheduled state, reorder, preview, banner uploads | backend | #106 (was #102) | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED) | ContentBannerModelIT 6/6 (real HTTP + Mongo) | — | review; campaign links + service-area targeting not implemented (no backend entity) |
| 10 | CMS media: metadata editor | web | #16 | MERGED_UNVERIFIED | 7/7 CI | — | merge with stack |
| 11 | CMS media: real upload, progress, preview, replace, retry | web | #21 | MERGED_UNVERIFIED (API-level journey INTEGRATED_VERIFIED locally; CMS UI not driven) | unit 433, e2e (fake backend + fake storage, no cookie/Authorization to storage asserted), CI 7/7 | depends on #95 | implement |
| 12 | CMS banners / rails / grids | web | #22 | MERGED_UNVERIFIED (API-level journey INTEGRATED_VERIFIED locally; CMS UI not driven) | unit 523, e2e 55, CI 7/7 | depends on #102 | implement |
| 13 | CMS preview (app / desktop / mobile web) | web + backend | #106 + #22 | MERGED_UNVERIFIED | admin-only preview endpoint (ContentBannerModelIT); CMS crops pinned to storefront/app ratios | — | implement |
| 14 | App: CMS banners/rails/grids, channel=app | app | #24 | IMPLEMENTED_UNMERGED | 1089 tests Android + iOS, CI green; pull-to-refresh, foreground refresh, backoff, no-flash rails, a11y, subtitle | needs #96 deployed | on-device check (pull gesture, foreground, TalkBack/VoiceOver) |
| 15 | App: product images from backend media | app | main | MERGED_UNVERIFIED | remote image pipeline (UI-03) | no real media bucket | E2E with staging storage |
| 16 | Customer website (home, banners, rails, grids, PDP gallery) | web | #20 + #26 | MERGED_UNVERIFIED (local INTEGRATED_VERIFIED; not deployed) | unit 114/114, e2e 13/13 (fake backend, channel=web asserted), CI 8/8 | shared rate-limit identity (see §5) | build storefront app |
| 17 | S3 bucket + CloudFront (staging) | infrastructure | #3 | BLOCKED_EXTERNAL | code written, `terraform validate` passes; gated off by default | usage-billed resources need CEO approval; infra #1/#2 unmerged | approve → bootstrap `allow_media_stack`, staging `enable_media` |
| 18 | Cross-channel E2E (16 journeys) | all | web #23 (local harness) | INTEGRATED_VERIFIED (local, on merged heads) | 2026-10-09 rerun on merged heads: CMS upload → storage → verification → publication → website display, APP/WEB/BOTH, schedule, unpublish, reorder, CDN failure fallback, 403 no-state-change, draft never public, and every rejection (bad MIME, oversize, missing, wrong owner, foreign CORS, write-once 412) PASS on real Mongo/Redis/Versity S3/CDN stand-in + production-mode website. App: JVM contract PASS; **on-device Android/iOS NOT_VERIFIED**. CMS steps API-driven (Google-only login, no bypass). | staging run needs infra approval | after the above |

## 4. Review remediation log

**#95 (storage)** — findings from the independent review and what was done:
- MAJOR overwrite-after-verify → presigned PUT now signs `If-None-Match: *` (write-once); `inspect` pins its ranged read
  to the HeadObject ETag (`If-Match`). Proven against Versity S3 Gateway (412 on re-PUT; 412 on a changed object).
  Scality CloudServer was replaced in that IT because it ignores `If-None-Match`.
- MAJOR 403-on-missing reported as outage → IAM guidance corrected (`s3:ListBucket` on the `p/` prefix is required so a
  missing key is 404; `s3:HeadObject` is not an IAM action).
- MINOR no timeouts → 2 s per attempt / 5 s per call.
- MINOR undeclared type not checked against stored label → stored Content-Type must equal the sniffed type.
- Open (minor, documented): owner-prefix sanitisation can collide for ids differing only in `.` vs `-`; existing keys
  are not re-verified on later writes; no dimension/decode check.

**#96 (targeting)**:
- one unreadable stored audience no longer 500s the public home (that block is hidden and logged).
- explicit `audience: null` is legacy BOTH in the query as well as the domain (`eq(null)` matches missing and null).
- an older client's PUT no longer rewrites a legacy document's audience.
- Open: CDN cache key must include the query string (`channel`) — enforced in MEDIA-INFRA / CloudFront cache policy;
  responses are `public, max-age=60` with no server cache.

## 5. Decisions needed from the CEO (with recommendations)

Resolved on 2026-10-09: backend #104, #95, #96 merged; CMS stack #5–#22 merged; disk freed. Still open:

### 5.1 Staging S3 + CloudFront spend (infra #1 → #2 → #3)
Usage-billed, expected ≈ $0 at staging volume (S3 ≈ $0.025/GB-month; CloudFront inside the free 1 TB / 10M requests).
Everything is gated off by default. **Needs explicit approval before `allow_media_stack` / `enable_media` are set.**
The exact `terraform plan` cannot be produced until an operator session exists (`aws login --profile tazzzo-ceo-admin`);
`fmt` and `validate` pass for both stacks.

### 5.2 Canonical product-id grammar — **DECIDED: `TZP-[A-Za-z0-9-]{1,40}`**
Implemented: app #24 `79173b6` (links, rail ids, PDP guard); backend #110 (cart validator + OpenAPI `ProductId` and cart `skuId`).
App cart check widens to match now that #110 is merged.
**Read-only audit (2026-10-09):** ~820 distinct ids across backend tests/fixtures/OpenAPI/docs (528), legacy catalog
service (250), web (~16), app (15) and research (15): **0 invalid** against the canonical grammar; longest 19 chars. There is
no live database yet (staging/production not provisioned), so this is the complete set of existing ids.
**5.2b open (needs approval):** the catalogue import / Mongo validator still accepts any `^TZP-` id, so it can create
products the cart refuses (e.g. `TZP-A.B`, > 40 chars). Enforcing the grammar at import is a non-backward-compatible change
to catalogue validation (CLAUDE.md: needs explicit approval). Audit done (0 invalid). **Recommendation:** enforce the grammar now, while no real data exists:
(a) `ProductImportValidator.shape` and product-create API reject non-conforming new ids (400 / `INVALID_ROW`) — a stricter
input contract, so it needs your approval; the planned crawler id scheme `TZP-{source}-{sha16}` must restrict `{source}` to
`[A-Za-z0-9-]`, ≤ 19 chars. (b) tighten the Mongo `products` validator (`_id`, `component_product_id`) via an explicit
`collMod` migration — `error` directly on an empty database, `warn` first on any database that already holds data. (c) no data
migration needed; a later rename would be expensive (`_id` re-mint plus cart, order, media, event and registry references).

### 5.3 Website rate-limit identity — **DECIDED and implemented without paid infrastructure**
- Backend #108: trusted server caller (`X-Tazzzo-Caller` + `X-Tazzzo-Caller-Secret`, SHA-256 + constant-time compare, two
  active secrets for zero-downtime rotation) charged to its own `caller` bucket.
- Storefront #26: per-visitor token buckets in `proxy.ts` (pages 60/min burst 20; `/search` and cursor pages 12/min burst 6),
  bounded LRU, IPv6 /64; genuine router prefetches exempt (measured 0 backend calls; assumption pinned by a policy test).
- **Deployment requirements (staging gate):** `STOREFRONT_TRUST_PROXY=true` behind the ALB with the app unreachable except
  through it (production refuses to start with the caller credential otherwise); backend reachable only from private
  networks; caller bucket (default 20000 / 2000 per s) sized by load test; alert on
  `trusted_caller.admissions{decision="rate_limited"}`. Limits are per storefront instance.
- Later: batch product read; WAF rate rule once infra spend is approved.

### 5.4 Extra image roles (FRONT, BACK, INGREDIENTS, NUTRITION, LIFESTYLE) — **DEFERRED (no launch blocker)**
`ImageRole` is PRIMARY | GALLERY and is parsed with `valueOf` in the backend, the app and the CMS. Adding values is a
three-repo contract change: (1) app and CMS must first tolerate unknown roles (today an unknown role would fail
deserialisation), ship that, then (2) the backend adds the enum values and ordering rules (one PRIMARY, roles per SKU vs
product), then (3) the CMS exposes them. No launch journey needs them; the gallery order already carries packaging shots.

### 5.5 Backend read gaps — **category-by-id implemented (#109); batch products later; category images later**
- **Category node by id** (`GET /v1/categories/{id}`): required for launch quality. Without it the app walks the taxonomy
  (up to 13 calls, ≈600 admission units) to name a grid tile and still misses deep nodes; the website cannot title deep
  category pages. Fits the existing taxonomy read service; one endpoint, cached like `/children`.
- **Batch products** (`GET /v1/products?ids=`, ≤ 20): removes the 20-calls-per-rail cost on both clients. Implement with 5.3.
- **Category images**: no backend model today (category media sets); the grids render with names only. Not launch-blocking.

## 6. Propagation (current, pre-CDN)

| Endpoint | Cache | Publish / unpublish delay | Emergency removal |
|---|---|---|---|
| `GET /v1/content/home?channel=` | `Cache-Control: public, max-age=60`, no server cache | ≤ 60 s (client/intermediary cache) | set status DRAFT or ARCHIVED in CMS; visible ≤ 60 s. With a CDN: plus a CloudFront invalidation of `/v1/content/home*` |
| App in-memory home cache | 60 s, no disk | ≤ 60 s after next Home composition | same |

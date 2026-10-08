# Tazzzo media & CMS content — completion matrix

Single source of truth for the media / CMS-content workstream (CMS → backend → object storage/CDN → Android, iOS,
website). Statuses are evidence-based; "code exists" is never "done".

Allowed statuses: `NOT_STARTED` · `PARTIAL` · `IMPLEMENTED_UNMERGED` · `MERGED_UNVERIFIED` · `INTEGRATED_VERIFIED` ·
`STAGING_VERIFIED` · `LIVE_VERIFIED` · `BLOCKED_EXTERNAL`.

Last audit: 2026-10-08 (live GitHub state; backend `main` = `e05f22e`, includes #93 and #98).

## 1. Existing PRs (Phase 0 audit)

| PR | Repo | Branch → base | Head | CI | Independent review | Merge |
|---|---|---|---|---|---|---|
| #95 S3-compatible media storage | backend | `feature/media-storage-s3` → `main` | `cbd714a` | 4/4 green (full suite in CI) | PASS_WITH_FOLLOWUPS → remediated; re-review PASS_WITH_FOLLOWUPS (minors fixed in `69b214c`) | unmerged, needs CEO approval | unmerged, needs CEO approval |
| #96 APP/WEB/BOTH targeting | backend | `feature/content-channel-audience` → `main` | `8b059bb` (main merged) | 4/4 green (full suite in CI) | PASS_WITH_FOLLOWUPS → remediated; re-review PASS_WITH_FOLLOWUPS | unmerged, needs CEO approval |
| #16 CMS media | web | `cms/07-media` → `cms/14-audit-status` (stack #5→#19) | `c3ab2cd` | 7/7 green | PASS_WITH_FOLLOWUPS — metadata editor only, **uploads no bytes** | unmerged; whole CMS stack unmerged |
| #24 App home content | app | `feature/app-home-content` → `main` | `c5caed0` | 4/4 green (linux + macos/iOS); 1096/1096 Android + iOS | PASS_WITH_FOLLOWUPS ×2 → all remediated | unmerged; **must not ship before #96 is deployed** |
| #102 banner model / reorder / preview (new) | backend | `feature/content-banner-model` → `feature/content-channel-audience` | `db3623c` | targeted ITs 28/28 local; CI runs once retargeted to `main` (workflow is main-only) | PASS_WITH_FOLLOWUPS → fixes in `6ee6d54`, `db3623c` | unmerged; retarget to main after #96 |
| web #20 customer storefront (new) | web | `web/01-storefront` → `main` | `7ff5e3b` | 9/9 green incl. storefront e2e dev 14/14 + production-build 4/4 | PASS_WITH_FOLLOWUPS → all code findings remediated | unmerged |
| infra #3 media S3 + CloudFront | infrastructure | `feature/infra-2-media` → `feature/infra-1-staging-foundation` | `b716efd` | fmt + validate both stacks (no CI in repo) | PASS_WITH_FOLLOWUPS → remediated | unmerged; gated off; needs spend approval |
| web #21 CMS media upload | web | `cms/20-media-upload` → `cms/16-qa-security` | `29d13cd` | 7/7 green | PASS_WITH_FOLLOWUPS → remediated | unmerged (CMS stack) |
| web #22 CMS home content | web | `cms/21-home-content` → `cms/20-media-upload` | `ef88d23` | 7/7 green | PASS_WITH_FOLLOWUPS → remediated | unmerged (CMS stack) |
| backend #105 integration preview (draft) | backend | `integration/media-content` → `main` | `198fc07` | **4/4 green: full suite on #95+#96+#102 combined** | n/a — DO NOT MERGE | carries the banner-outage→503 fix into #102 after #95 merges |
| backend #104 flaky checkout snapshot tests (new) | backend | `fix/checkout-money-snapshot-flaky` → `main` | see PR | — | test-only | unmerged |
| web #23 local E2E harness (draft) | web | `e2e/media-content-local` → `main` | `7bcb44e` | 11/11 Playwright journeys, 5.1 min, stack rebuilt from scratch | evidence in `e2e/local-stack/EVIDENCE_2026-10-08.md` | DO NOT MERGE to main as product; harness only |
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
| 1 | Media upload init (presigned PUT) | backend | #95 | IMPLEMENTED_UNMERGED | S3MediaStorageIT, MediaUploadEndToEndIT (S3Mock), S3SignatureEnforcementIT (Versity S3 Gateway, SigV4 + preconditions) | — | CEO merge approval |
| 2 | Magic-byte / MIME / size verification at reference time | backend | main + #95 | IMPLEMENTED_UNMERGED | MediaIngestUnitTest, E2E IT refusals | — | dimension/decode check not implemented (follow-up) |
| 3 | Write-once uploads (no overwrite of verified bytes) | backend | #95 remediation | IMPLEMENTED_UNMERGED | S3SignatureEnforcementIT: re-PUT → 412 | — | push + re-review |
| 4 | Product media set metadata (roles, alt, order, primary) | backend | main | MERGED_UNVERIFIED | existing ITs; roles limited to PRIMARY/GALLERY | — | role expansion decision (see §5) |
| 5 | Public delivery URL / CDN base URL | backend | main (`MediaUrlResolver`) | PARTIAL | unit tests only; no CDN exists | CDN not provisioned | MEDIA-INFRA |
| 6 | Cache-Control / versioned URLs / invalidation | backend + infra | — | NOT_STARTED | keys are unique per upload (implicitly versioned) | CDN | define in MEDIA-INFRA |
| 7 | Unreferenced-object reaper / lifecycle | backend + infra | — | NOT_STARTED | — | bucket | follow-up PR |
| 8 | Channel targeting APP/WEB/BOTH (backend) | backend | #96 | IMPLEMENTED_UNMERGED | ContentChannelTargetingIT (real HTTP + Mongo) 5→7 tests | — | push remediation; CEO merge approval |
| 9 | Banner model: subtitle, desktop image, alt, createdBy/updatedBy, derived scheduled state, reorder, preview, banner uploads | backend | #102 | IMPLEMENTED_UNMERGED | ContentBannerModelIT 6/6 (real HTTP + Mongo) | — | review; campaign links + service-area targeting not implemented (no backend entity) |
| 10 | CMS media: metadata editor | web | #16 | IMPLEMENTED_UNMERGED | 7/7 CI | — | merge with stack |
| 11 | CMS media: real upload, progress, preview, replace, retry | web | #21 | IMPLEMENTED_UNMERGED | unit 433, e2e (fake backend + fake storage, no cookie/Authorization to storage asserted), CI 7/7 | depends on #95 | implement |
| 12 | CMS banners / rails / grids | web | #22 | IMPLEMENTED_UNMERGED | unit 523, e2e 55, CI 7/7 | depends on #102 | implement |
| 13 | CMS preview (app / desktop / mobile web) | web + backend | #102 + #22 | IMPLEMENTED_UNMERGED | admin-only preview endpoint (ContentBannerModelIT); CMS crops pinned to storefront/app ratios | — | implement |
| 14 | App: CMS banners/rails/grids, channel=app | app | #24 | IMPLEMENTED_UNMERGED | 1089 tests Android + iOS, CI green; pull-to-refresh, foreground refresh, backoff, no-flash rails, a11y, subtitle | needs #96 deployed | on-device check (pull gesture, foreground, TalkBack/VoiceOver) |
| 15 | App: product images from backend media | app | main | MERGED_UNVERIFIED | remote image pipeline (UI-03) | no real media bucket | E2E with staging storage |
| 16 | Customer website (home, banners, rails, grids, PDP gallery) | web | #20 | IMPLEMENTED_UNMERGED | unit 114/114, e2e 13/13 (fake backend, channel=web asserted), CI 8/8 | shared rate-limit identity (see §5) | build storefront app |
| 17 | S3 bucket + CloudFront (staging) | infrastructure | #3 | BLOCKED_EXTERNAL | code written, `terraform validate` passes; gated off by default | usage-billed resources need CEO approval; infra #1/#2 unmerged | approve → bootstrap `allow_media_stack`, staging `enable_media` |
| 18 | Cross-channel E2E (TEST 1–16) | all | web #23 (local harness) | INTEGRATED_VERIFIED (local) | TEST 1–4, 7–16 PASS on real backend + S3-compatible store + CDN stand-in + production-mode website; TEST 5/6 app: JVM contract PASS, **on-device NOT_VERIFIED** (debug app has no local base-URL override; iOS not attempted); CMS steps API-driven (CMS has Google-only login, no bypass added) | staging run needs infra approval | after the above |

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

### 5.2 Canonical product-id grammar — **recommend: adopt `TZP-[A-Za-z0-9-]{1,40}` everywhere (the backend's)**
| Where | Today |
|---|---|
| backend `ContentBlock` links/rails, OpenAPI content | `TZP-[A-Za-z0-9-]{1,40}` |
| backend OpenAPI product path, cart | `^TZP-[0-9]+$` / `^TZP-[0-9]{1,18}$` |
| CMS, storefront | `TZP-[A-Za-z0-9-]{1,40}` |
| app (`ContentModels.kt`) | `^TZP-[0-9]+$` |
Consequence of the recommendation: one app change (widen the regex, keep length ≤ 40 and the `TZP-` prefix) plus aligning
the two narrower backend OpenAPI patterns; no data migration, since seeded ids today are numeric and the wider grammar is a
superset. Rejecting it (numeric-only) would instead force the backend, CMS and storefront to narrow and would forbid ids such
as `TZP-MED-3` that the import pipeline already accepts. Risk either way is low; the cost asymmetry favours widening.

### 5.3 Website rate-limit identity — **recommend for launch: dedicated storefront identity + edge per-IP limit; batch read later**
To the backend the whole storefront is one client IP, so one visitor requesting random valid-shaped product ids can drain
the shared admission bucket for everyone (confirmed in the #20 review).
| Option | Effect | Cost / risk |
|---|---|---|
| A. Dedicated storefront identity (a trusted server header → its own, larger bucket) | isolates the website from app/anon traffic; sized for server-side rendering | small backend change (trusted-caller allowlist by secret header or mTLS), config |
| B. Edge per-IP throttling (CloudFront + WAF rate rule, or Next middleware per-IP) in front of the storefront | stops one visitor from exhausting A | WAF is a paid resource and currently denied by the infra guardrails; Next middleware is free but per-instance |
| C. Batch product read (`GET /v1/products?ids=`) | cuts a 20-item rail from 20 calls to 1 | backend addition; also helps the app |
Safest launch set: **A + Next-middleware per-IP limit now; C next; WAF when infra spend is approved.** A alone still lets one
visitor starve others; B alone still shares the bucket with the app.

### 5.4 Extra image roles (FRONT, BACK, INGREDIENTS, NUTRITION, LIFESTYLE) — **recommend: defer; do not implement yet**
`ImageRole` is PRIMARY | GALLERY and is parsed with `valueOf` in the backend, the app and the CMS. Adding values is a
three-repo contract change: (1) app and CMS must first tolerate unknown roles (today an unknown role would fail
deserialisation), ship that, then (2) the backend adds the enum values and ordering rules (one PRIMARY, roles per SKU vs
product), then (3) the CMS exposes them. No launch journey needs them; the gallery order already carries packaging shots.

### 5.5 Backend read gaps — **recommend: implement category-by-id now; batch products with 5.3; category images later**
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

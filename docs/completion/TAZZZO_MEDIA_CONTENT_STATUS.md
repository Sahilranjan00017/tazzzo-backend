# Tazzzo media & CMS content — completion matrix

Single source of truth for the media / CMS-content workstream (CMS → backend → object storage/CDN → Android, iOS,
website). Statuses are evidence-based; "code exists" is never "done".

Allowed statuses: `NOT_STARTED` · `PARTIAL` · `IMPLEMENTED_UNMERGED` · `MERGED_UNVERIFIED` · `INTEGRATED_VERIFIED` ·
`STAGING_VERIFIED` · `LIVE_VERIFIED` · `BLOCKED_EXTERNAL`.

Last audit: 2026-10-08 (live GitHub state; backend `main` = `e05f22e`, includes #93 and #98).

## 1. Existing PRs (Phase 0 audit)

| PR | Repo | Branch → base | Head | CI | Independent review | Merge |
|---|---|---|---|---|---|---|
| #95 S3-compatible media storage | backend | `feature/media-storage-s3` → `main` | `68f3a07` (main merged in at `9f94c41`, remediation on top) | pre-remediation 4/4 green; remediation: full local suite 3264/0 failures, CI re-running | PASS_WITH_FOLLOWUPS (2 major, 5 minor) → remediated, re-review running | unmerged, needs CEO approval |
| #96 APP/WEB/BOTH targeting | backend | `feature/content-channel-audience` → `main` | `a41e619` | targeted ITs 17/17 local; CI re-running | PASS_WITH_FOLLOWUPS (no blocker) → remediated, re-review running | unmerged, needs CEO approval |
| #16 CMS media | web | `cms/07-media` → `cms/14-audit-status` (stack #5→#19) | `c3ab2cd` | 7/7 green | PASS_WITH_FOLLOWUPS — metadata editor only, **uploads no bytes** | unmerged; whole CMS stack unmerged |
| #24 App home content | app | `feature/app-home-content` → `main` | `6e2c4e2` | 4/4 green (linux + macos/iOS) | PASS_WITH_FOLLOWUPS; 1065/1065 unit tests | unmerged; **must not ship before #96 is deployed** |
| #102 banner model / reorder / preview (new) | backend | `feature/content-banner-model` → `feature/content-channel-audience` | `cdac046` | targeted ITs 25/25 local; CI running | review running | unmerged; retarget to main after #96 |
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
| 11 | CMS media: real upload, progress, preview, replace, retry | web | MEDIA-CMS (not opened) | NOT_STARTED | review: "uploads no bytes" | **local disk full (<1.5 GB) blocks build/test**; depends on #95 | implement |
| 12 | CMS banners / rails / grids | web | CONTENT-CMS (not opened) | NOT_STARTED | nav lists "Home content" as planned; no page | **local disk full**; contract ready in #102 | implement |
| 13 | CMS preview (app / desktop / mobile web) | web + backend | — | NOT_STARTED | — | depends on 9, 12 | implement |
| 14 | App: CMS banners/rails/grids, channel=app | app | #24 | IMPLEMENTED_UNMERGED | 1065 unit tests; iOS compiles in CI | needs #96 deployed; **local disk full blocks remediation** | follow-ups: grid non-root ids, refresh, rail flash, thread safety, a11y, subtitle |
| 15 | App: product images from backend media | app | main | MERGED_UNVERIFIED | remote image pipeline (UI-03) | no real media bucket | E2E with staging storage |
| 16 | Customer website (home, banners, rails, grids, PDP gallery) | web | CONTENT-WEB (not opened) | NOT_STARTED | `tazzzo-web/apps` contains only `admin` | **local disk full** | build storefront app |
| 17 | S3 bucket + CloudFront (staging) | infrastructure | MEDIA-INFRA | BLOCKED_EXTERNAL | — | paid AWS resources need CEO approval; infra #1/#2 unmerged | Terraform can be written; apply needs approval |
| 18 | Cross-channel E2E (TEST 1–16) | all | E2E-MEDIA | NOT_STARTED | — | needs 11, 12, 16, staging | after the above |

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

## 5. Decisions needed from the CEO

0. **Free local disk** (this Mac had 0.7–2.7 GB free on 2026-10-08; Docker went read-only once). Client workstreams (CMS, website, app) need ≥ 5 GB to build and run e2e. Largest regenerable items: Docker Desktop data (16 GB), `~/.gradle/caches` (7.9 GB), finished `tazzzo-backend-*` worktrees, `tazzzo-app/composeApp/build` (1.3 GB), `~/.npm` (1.2 GB).

1. **Merge approval** for #95 and #96 (after re-review of the remediation).
2. **Paid resources**: S3 bucket + CloudFront for staging (infra #1/#2 + MEDIA-INFRA).
3. **Image roles**: the backend `ImageRole` contract has only PRIMARY and GALLERY. Adding FRONT_PACK, BACK_PACK,
   NUTRITION, INGREDIENTS, LIFESTYLE is a cross-repo contract change (backend + app must tolerate unknown roles first).
4. **Banner model extension** (CONTENT-BE-2): additive fields on the public `/v1/content/home` response.

## 6. Propagation (current, pre-CDN)

| Endpoint | Cache | Publish / unpublish delay | Emergency removal |
|---|---|---|---|
| `GET /v1/content/home?channel=` | `Cache-Control: public, max-age=60`, no server cache | ≤ 60 s (client/intermediary cache) | set status DRAFT or ARCHIVED in CMS; visible ≤ 60 s. With a CDN: plus a CloudFront invalidation of `/v1/content/home*` |
| App in-memory home cache | 60 s, no disk | ≤ 60 s after next Home composition | same |

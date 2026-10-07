# TAZZZO — PR tracker

Every PR the completion program touches. "Review" = independent adversarial review by a reviewer other than the author (a fresh-context agent plus mechanical re-run when the author is this program).

## Backend (Sahilranjan00017/tazzzo-backend)

| PR | Scope | Head SHA | CI (exact head) | Review | Merge state | Merge SHA |
|---|---|---|---|---|---|---|
| #91 | Dependency security: Tomcat 10.1.60, Jackson 2.18.11, Netty 4.1.139, Nimbus 9.37.4; multipart off | `cc943258bfdeb044ed3ea39ac1cf453aa42de9c7` | run 37450250464 ✅ | PASS (class B) | MERGED (CEO-approved pinned squash) | `d7905045dea62849e138d64df3d49942c7be1b74` |
| #92 | Map malformed-request framework errors to 400/415/406 at every /v1 error boundary | `2732edfb151446fafc942d2fc2f866ec5aa2ec4d` | run 37526530157 ✅ | PASS (class B) | MERGED (CEO-approved pinned squash) | `7d491dd027d09d5dc4b355205db9ce11e02dd6b9` |
| #93 | Negotiate Accept before the handler runs; JSON error bodies on every advice | `583223686de0d49b4595267f653b9a33fcdf07c3` | run 37536569953 ✅ (3,269 tests) | **PASS** 2026-10-07 — see below | OPEN, MERGEABLE/CLEAN, base = main `7d491dd` | — (pinned command below) |
| #94 | Completion trackers (docs only) | `aa6db11` + updates | n/a (docs) | self (docs) | OPEN | — |
| #95 | MEDIA-STORAGE: S3-compatible `MediaStorage` (presigned upload, inspection) | `03ea97b968f6e7b0acbb4c22a852f4b83b037505` (after review fixes; first head `1f59b28`) | first head: run 37545551274 ✅ (3,257); remediated head: run 37549935119 ✅ on attempt 2 (3,261; attempt 1 failed only the pre-existing flaky `CheckoutQuoteIT` assertion) | first head **FAIL** (HIGH: `apache5-client` shipped; MEDIUM: duplicate Content-Type keys, outage→500, cross-owner key reference; LOW: endpoint validation, unbounded size); **all fixed** at `03ea97b`; re-review **PASS** (new LOW: owner-id normalisation could alias ids differing only in non-key-safe characters; NOTE: startup HeadBucket probe would make a wrong bucket explicit) | OPEN, awaiting CEO merge approval | — |
| #96 | CHANNEL-PUBLISHING: `audience` + `?channel=app\|web` (D1–D3) | `d952ed04613967c57c550476a65d75a10997d415` | run 37548402098 ✅ (3,253) | **PASS** (deployment note: CDN cache key must include `channel`; LOW: corrupt stored audience → 400) | OPEN, awaiting CEO merge approval | — |
| #97 | CATALOGUE-SCALE: capacity harness `CatalogCapacityIT` (test + runbook only) | `ad4d799329e0f3c43319ebf16ebe8cf23c5d2363` (hardening: assert 100 % 2xx, winning-plan explain, runbook caveats) | run 37553486818 ✅ on `58190ab`; run 37554653821 ✅ on `ad4d799` | **PASS** (review ran its own 2,000-SKU run; MEDIUM: non-2xx not asserted, explain string-search — both fixed; LOW doc notes — fixed) | OPEN, awaiting CEO merge approval after CI on the hardening head | — |
| #98 | Flaky `CheckoutQuoteIT` assertion (field names instead of whole body) | `308332d38064ded6065b96a4616987df2bf48480` | run 37553781674 ✅ | test-only, 30 lines, reviewed by reading; `CheckoutQuoteIT` 73/73 | OPEN, awaiting CEO merge approval | — |
| #99 | CATALOGUE-SCALE: pace projection reconciliation to the catalogue size | `ac4ed5579b123a7ce0cc66faacf7da347741f9a3` | pending | pending (fresh agent) | OPEN | — |
| #83 | Dependabot: Spring Boot 4.1.1 | `553bc83` | — | known: does not compile | OPEN | — |
| #82,#84–#89 | Dependabot actions/library bumps | various | — | not reviewed | OPEN | — |

### PR #93 independent review record (Phase 0)
- Provenance: OPEN, 1 commit, 18 files, base = current main `7d491dd`, mergeable clean; ruleset `protect-main` active, no bypass actors; all 4 checks green on the exact head.
- Diff scope: 2 new classes (`AcceptNegotiationInterceptor`, `AcceptNegotiationConfig`), 9 advices gain only `.contentType(APPLICATION_JSON)`, 3 new tests, 1 updated test, OpenAPI (+406 on 37 ops, +415 on 11 ops, validated), 2 docs. No filter/auth/rate-limit/body-limit/route/migration/business change.
- Ordering: servlet filters run before any interceptor; probed 401 (no token + XML), 413 (70 KB body + XML), 429 (exhausted budget + XML), 403 (wrong role).
- Zero-write: refused address/cart/quote/COD order/support/profile/deletion leave no document, no stock movement, no idempotency key (IT asserts counts by customer; on base 6/8 of those tests fail = reproduction). 
- Accept matrix: identical to Spring's writer on base for every value probed (`*/*`, `application/*`, vendor `+json`, `problem+json`, charset, q-weighted); q=0 is ignored on both (Spring parity, not a regression); `?format=xml` cannot bypass.
- HEAD/void/204/304: HEAD+XML 406 (base leaked ETag, fixed); `ResponseEntity<Void>` and logout untouched (204); **behaviour change:** `If-None-Match` + non-JSON Accept now 406 instead of 304 (LOW, documented).
- Nine advices: anonymous `/catalog/v1/products/X` + XML: base 500 empty body + 154 stack lines → head 400 JSON, 0 ERROR; admin price + XML: base 500 → head 406 JSON.
- Mutations N1–N9: all compiled, all detected (27, 28, 16, 2, 1, 1, 1, 1, 1 killing tests).
- Full suite on exact head: 3,269 / 0 failures; secret scan clean (1,000 files); DB/contract files unchanged; container uid 10001, no secrets, 73 libs, 0 test libs.
- Findings: MEDIUM pre-existing — `/health/*` answer 400 to non-JSON Accept (not introduced; follow-up exemption); LOW — structural guard evadable by `new ResponseEntity<>()`/`ResponseEntity.of`; NOTE — 406 now precedes 400/415.
- Verdict: **PASS — safe to squash-merge pinned at `583223686de0d49b4595267f653b9a33fcdf07c3`.** Not merged; awaiting CEO approval.

```
gh pr merge 93 --squash --match-head-commit 583223686de0d49b4595267f653b9a33fcdf07c3
```

### PR #95 remediation record
Fixes at `03ea97b`: `apache5-client` excluded (runtime classpath and image verified: 0 `httpclient`/`httpcore` jars; they remain test-scoped only); canonical upload headers with a signed `Content-Length` (a larger body → 403 on CloudServer); `MediaStorageFailure` → 503 `MEDIA_STORAGE_UNAVAILABLE` (WARN, class name only); newly referenced keys must start with `p/<ownerType>/<ownerId>/` (422 otherwise); endpoint validation (http(s) only, no userinfo, http only for loopback/`host.docker.internal`/single-label); provider validated at startup; CloudServer pinned by digest; docs corrected. Mutations M1–M6: M1 (type unbound) killed by the URL-contract test and the CloudServer IT; M2 (missing reported present) killed ×3; M3 (head too short) killed ×4; M4 (http anywhere) killed; M6 (unsafe key) killed; M5 (`matchIfMissing=false`) equivalent (application.yml always sets the provider) — confirmed by the reviewer. Full suite on the remediated branch: 3,261 / 0; secret scan clean; image uid 10001, 103 libs, 0 test libs.

### PR #96 review record
Fresh-agent audit: public response for existing callers byte-identical except requestId (probed on base and head with identical seeded docs); leak analysis over every reader of `content_blocks` (query filter and domain re-check admit identical sets for app/web/null); parameter grammar closed (`?Channel`, `%20`, repeated, `;x`, FAQ/app-config with `channel` → 400); explain shows the same `IXSCAN content_by_placement_status_sort` plan with audience as a residual predicate; OpenAPI valid; `ApiContractParityIT` is path-only, the YAML parameter is the guard. Mutations C1–C7 all killed (legacy hidden, app cannot see app-only, extra params accepted, older client erases, HELP targetable, reads as BOTH, channel case-insensitive). Full suite 3,253 / 0; secret scan clean; spec valid; image uid 10001.

```
gh pr merge 96 --squash --match-head-commit d952ed04613967c57c550476a65d75a10997d415
gh pr merge 95 --squash --match-head-commit 03ea97b968f6e7b0acbb4c22a852f4b83b037505
gh pr merge 98 --squash --match-head-commit 308332d38064ded6065b96a4616987df2bf48480
gh pr merge 97 --squash --match-head-commit ad4d799329e0f3c43319ebf16ebe8cf23c5d2363
```

### PR #99 record (pending review)
Mutations P1–P6 (no ceil, floor ignored, cap ignored, recount every pass, scheduler unpaced, no startup validation): all killed after `CommerceProjectionSchedulerPacingIT` and the config test were added (P5/P6 had survived before them). Full suite 3,254/0 (before the last two tests), secret scan clean, container uid 10001. Follow-up noted: the drain batch (200/15 s) should scale too beyond ~190k SKUs.

**PR #99 review record (2026-10-07):** CI on `ac4ed55` green (Compile & test, Secret scan, Container build, Validate API contracts; 3,256/0). Independent fresh-context review: **PASS** — 0 HIGH; MEDIUM-1 the drain-batch follow-up was only in this tracker, not `docs/ENGINEERING_STATUS.md` → fixed in `8b6ac63` (status now records the limit at ≈192k SKUs and the freshness-latency consequence: `ProjectionRebuildWorker.claim` is natural-order, so a reconcile backlog delays hook rebuilds); MEDIUM-2 same consequence, documented; LOW fixedDelay drift (a 20,000-row tick takes ≈24 s → a 1M pass ≈4.6 h, `bulkWrite` would fix, pre-existing code); LOW orphan count is a COLLSCAN of `product_card_base` once per 4 h (`estimatedDocumentCount()` would be O(1)); NOTEs on the +1 wrap tick, the duplicated `card-reconcile-ms` literal, one arithmetic-only assertion. **New head `8b6ac63` (docs-only on top of `ac4ed55`); CI on that head NOT yet verified** — run `gh pr checks 99` before the pinned merge `gh pr merge 99 --squash --match-head-commit 8b6ac634035b33e1726f93efed30ed1f0ea1848a`.

**Async import engine (branch `feature/async-import-jobs`, worktree `tazzzo-backend-imports`, from `main` `7d491dd`): IN PROGRESS, uncommitted, not yet compiled.** Written: `bulkimport/jobs/ImportCsvParser.java` (streaming RFC 4180, CMS column aliases), `ImportJob.java` (job record/status machine), `ImportJobRepository.java` (`import_jobs`/`import_rows`, CAS transitions, lease claim/progress), and `ProductImportValidator.validateRows(...)` (per-row, non-throwing; `validate` now delegates to it; class made public). Remaining: `ImportJobService`, `ImportJobWorker`, scheduler (`tazzzo.scheduler.import-jobs-enabled`; add `ImportJobScheduler.java: 1` to `ScheduledWorkerGateTest`), controller under `/api/v1/admin/imports/jobs` (bulk body-limit prefix) + exception handler, `IndexCatalog` specs + `V0016__import_job_indexes` in `Migrations` (MANAGED list), `SchemaBootstrap.COLLECTIONS`, `application.yml` keys, test pins (`MigrationRegistryTest` RELEASED V0016 checksum, `DatastorePrivilegeIT` hasSize 15→16, `IndexContractIT` MANIFEST, `DatabaseDocsConsistencyTest`), docs (`DATABASE_RETENTION_AND_PII` matrix rows, `DATABASE_INVENTORY`, `DATABASE_INDEX_MANIFEST` §10d, `DATABASE_MIGRATION_RUNBOOK` table + step list, `BULK_IMPORT.md`, `ENGINEERING_STATUS`), tests `ImportCsvParserTest`, `ImportJobsIT`, mutations, full suite, PR, CI, review.


### Flaky test on record
`CheckoutQuoteIT.the_http_quote_exposes_the_exact_money_preview_beside_the_unchanged_benefit_preview:1603` asserts the lower-cased response body `doesNotContain("tax")`; a random quote id (`chkq_h28tax8f…`) contained it (run 37549935119 attempt 1). Passes 3/3 locally. Fix separately: assert on field names, not the whole body. Also previously flaky in CI history: `DatastorePrivilegeIT` (run 37247884928), `OtpServiceIT.resend_race…` (36349865644), `AddressServiceabilityHttpIT` (37520547831).

## CMS / website (Sahilranjan00017/tazzzo-web)

| PR | Scope | State | Base | Notes |
|---|---|---|---|---|
| #1–#4 | W1–W4 foundation, OIDC+session, BFF, shell | MERGED | main | main = `175e609` |
| #5 | contract matrix doc + checkpoint | OPEN | main | stack root |
| #6 | Dashboard | OPEN | cms/00 | |
| #7 | multichannel scope proposal (docs) | OPEN | main | not in stack; D1–D9 approved, implementation not authorised |
| #8 | Products | OPEN | cms/01 | |
| #9 | Taxonomy | OPEN | cms/02 | |
| #10 | Pricing + inventory | OPEN | cms/03 | |
| #11 | Imports (CSV) | OPEN | cms/04 | |
| #12 | Orders | OPEN | cms/06 | |
| #13 | Support | OPEN | cms/08 | |
| #14 | Service areas + slots | OPEN | cms/11 | |
| #15 | Audit, notifications, status | OPEN | cms/09 | |
| #16 | Media metadata + upload readiness | OPEN | cms/14 | upload blocked: backend 503 MEDIA_STORAGE_NOT_CONFIGURED |
| #17 | FAQs + app config | OPEN | cms/07 | |
| #18 | RBAC matrix, goto box | OPEN | cms/13 | |
| #19 | QA/security sweep, readiness docs | OPEN | cms/12b | stack tip `1a05e04`, web-ci 7/7 |

All 15 open CMS PRs: web-ci SUCCESS, mergeable, **never run against a real backend** (`docs/cms/CMS_INTEGRATION_EVIDENCE.md`). Merge requires owner approval in stack order #5→#19 (+#7).

## App / infrastructure
No PRs opened by this program yet.

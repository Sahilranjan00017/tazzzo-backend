# TAZZZO — External blockers (require CEO / owner action)

| # | Blocker | Blocks | What is needed | Status |
|---|---|---|---|---|
| B1 | Merge approval for backend PR #93 | backend main advance, Phase 3+ branches rebase cleanly | `gh pr merge 93 --squash --match-head-commit 583223686de0d49b4595267f653b9a33fcdf07c3` (or approve the permission prompt) | OPEN |
| B2 | 5,000-SKU verified product master | launch data import, TEST 10, completion item 15 | the dataset (CSV/XLSX) with verified fields, or its location | OPEN — not in any repo (only 50-SKU validation files in tazzzo-research) |
| B3 | Paid object storage + CDN | live media upload, CDN URLs, TEST 3/4/5, completion items 4–8 | approve S3 bucket + CloudFront for `cdn.tazzzo.com` (ap-south-1); est. < $5/month at launch volume | OPEN — local/test adapter proceeds without it |
| B4 | Channel-targeting authorisation | Phase 5 banners/APP-WEBSITE-BOTH | approve implementation of web PR #7 proposal (D1–D9) in backend | OPEN |
| B5 | CMS stack merge order | Phase 2, staging CMS | approve merging tazzzo-web #5→#19 (+#7 docs) | OPEN |
| B6 | Staging runtime budget | Phase 13: ALB, ECS services, Valkey, Atlas cluster, DNS/TLS | approve ~$80/month staging (INFRA_0 estimate) or a reduced smoke-test profile | OPEN |
| B7 | Google OIDC client for staging CMS | staging CMS login | client id/secret in SSM; backend audience must match | OPEN |
| B8 | MongoDB Atlas cluster | staging datastore, migration dry run | Atlas project/cluster + users (DB-4 runbook) | OPEN |
| B9 | Scheduled-pricing ratification | Pricing effective dates (CMS row) | overturn/extend ratified "immediate-only" Option A | OPEN |
| B10 | Search infrastructure decision | Phase 9 ranking/facets | Atlas Search (needs Atlas ≥ M10) vs self-hosted OpenSearch vs Mongo-only | OPEN |
| B11 | Website hosting/domain | Phase 7 `www.tazzzo.com` | hosting target, TLS | OPEN |

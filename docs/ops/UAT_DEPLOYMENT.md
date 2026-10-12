# UAT deployment pipeline (GitHub Actions to AWS)

Status: the workflows are **written and statically linted only**. No AWS API was called while writing them, and nothing has been deployed. Every AWS step is **NOT RUN / NOT VERIFIED** until the infrastructure exists and an authorised person triggers the first run.

Scope: this repo owns build, test, scan, push, trigger, verify and report. The instance, the compose file, the SSM document `tazzzo-uat-deploy`, ECR, IAM and the OIDC provider are owned by `tazzzo-infrastructure`.

## Files
| File | Purpose |
|---|---|
| `.github/workflows/deploy-uat.yml` | build, test, scan, push, deploy, verify, report |
| `.github/workflows/rollback-uat.yml` | redeploy an earlier image (manual) |
| `scripts/uat-ssm-run.sh` | `aws ssm send-command` + poll, sanitized output |
| `scripts/uat-wait-healthy.sh` | poll `/health/ready` then `/health/live`, fail fast on an unmigrated schema |
| `scripts/uat-smoke-test.sh` | unauthenticated smoke test, runnable locally against any base URL |

## Contract with the infrastructure repo
| Item | Value |
|---|---|
| Region | `ap-south-1` |
| ECR repository | `tazzzo-uat-backend`, tags **IMMUTABLE**, only the full 40-hex git SHA, **no `latest` tag ever** |
| Image URI | `<account>.dkr.ecr.ap-south-1.amazonaws.com/tazzzo-uat-backend:<full-sha>` |
| GitHub environment | `uat` |
| Deploy mechanism | SSM Run Command, document `tazzzo-uat-deploy`, parameters `GitSha` (40 hex) and `Action` (`deploy` or `rollback`); targets tags `Project=Tazzzo`, `Environment=uat`, `Role=backend`; no SSH |
| State | non-secret SSM String parameters `/tazzzo/uat/backend/deployed-sha` and `/tazzzo/uat/backend/previous-sha`, written by the document |
| Architecture | arm64 (t4g.small) by default |

### GitHub repository variables (names only; none is a secret)
`AWS_REGION` (ap-south-1), `AWS_UAT_DEPLOY_ROLE_ARN`, `UAT_API_BASE_URL` (for example `https://api-uat.tazzzo.com`), `UAT_EC2_ROLE_TAG` (`backend`), `UAT_IMAGE_PLATFORM` (default `linux/arm64`), `UAT_RUNNER_LABEL` (default `ubuntu-24.04-arm`).

There are **no AWS secrets**: no `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` is read or accepted anywhere. Authentication is OIDC only.

### GitHub environment `uat` (owner configures; a repository setting, not code)
Create the environment `uat` and add **required reviewers**. Both AWS jobs (`publish`, `deploy`) and the rollback job declare `environment: uat`, so each waits for approval (two approvals per deploy). Restrict deployment branches to `uat` (and the dispatching branches you trust). The build job has no environment and no AWS access.

### OIDC trust statement the infra repo must satisfy
- An IAM OIDC provider for `token.actions.githubusercontent.com` with audience `sts.amazonaws.com`.
- A role (its ARN goes in `AWS_UAT_DEPLOY_ROLE_ARN`) whose trust policy allows `sts:AssumeRoleWithWebIdentity` only when `token.actions.githubusercontent.com:aud` is `sts.amazonaws.com` and `token.actions.githubusercontent.com:sub` equals exactly `repo:Sahilranjan00017/tazzzo-backend:environment:uat`.
- Permissions, least privilege: ECR push and read on `tazzzo-uat-backend` only (`GetAuthorizationToken`, `BatchCheckLayerAvailability`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`, `BatchGetImage`, `DescribeImages`, `DescribeImageScanFindings`, `StartImageScan`); `ssm:SendCommand` on the document `tazzzo-uat-deploy` and on instances tagged as above; `ssm:GetCommandInvocation`, `ssm:ListCommandInvocations`; `ssm:GetParameter` on the two `/tazzzo/uat/backend/*-sha` parameters.
- ECR repository: `imageTagMutability=IMMUTABLE`, scan on push enabled (basic scanning).

## Triggers, and why not main
`push` to the branch **`uat` only**, plus `workflow_dispatch` (input `ref`, default `uat`). It never runs on pushes to `main`: UAT has a running cost and a shared database, so a deploy is a deliberate act (fast-forward `uat` to the commit to test), not a side effect of merging. Pull requests never run it (forks get no OIDC token). Concurrency group `uat-deploy` serialises deploys and rollbacks and never cancels a running one.

Dispatch inputs: `auto_rollback` (default false), `reuse_existing_image` (default false).

## Pipeline
1. **build** (arm64 runner `ubuntu-24.04-arm`, no AWS): checkout; `GIT_SHA` is `git rev-parse HEAD` of the checked-out ref (equal to `github.sha` on a push; with a dispatch for another ref it is the SHA actually built); Java 21 Temurin with Maven cache; `./mvnw -B verify` (the same full Testcontainers suite `backend-ci.yml` runs, plus packaging; the jar is reused for the image); `docker buildx build --platform $UAT_IMAGE_PLATFORM` with the existing Dockerfile; the repo's image assertions (uid 10001, no configuration or secrets in the image environment) plus an architecture check; Trivy scan with SARIF upload (best effort, needs code scanning) and a gate that fails on **fixable** HIGH or CRITICAL; the scanned image is handed to the next job as a one-day artifact.
2. **publish** (environment `uat`, OIDC): assume the role; ECR login; if the tag `<full-sha>` already exists the workflow does **not** overwrite it (immutable). A rebuild is never bit-identical, so equality with the new build cannot be proven; it fails unless `reuse_existing_image=true` (then the existing image is deployed). Otherwise it pushes only `<full-sha>` and reads the digest from ECR. It then waits (up to 10 minutes) for the ECR scan and fails on any CRITICAL finding.
3. **deploy** (environment `uat`, OIDC): reads the SHA running now (rollback target); `aws ssm send-command` (`Action=deploy`) and polls to Success/Failed/TimedOut (10 minutes), printing only statuses and sanitized output (lines that look like credentials or connection strings are dropped; parameter values are never printed); polls `/health/ready` up to 5 minutes and checks `/health/live`; runs `scripts/uat-smoke-test.sh`; writes the report to `$GITHUB_STEP_SUMMARY` (deployed SHA, image, digest, previous SHA, SSM, health and smoke results) and exits non-zero on any failure.
4. **On failure after the SSM deploy started**: the job prints the rollback instruction (`rollback-uat` with the previous SHA). With `auto_rollback=true` it runs `Action=rollback` itself, then checks health; the run still ends red.

The previous SHA is the value of `/tazzzo/uat/backend/deployed-sha` read **before** the deploy (what is running now). If that parameter is empty (first deploy) it falls back to `/tazzzo/uat/backend/previous-sha`; if neither exists there is nothing to roll back to.

## Migration authorisation boundary
The pipeline never touches the database. The API starts in `VERIFY` mode and **refuses an unmigrated or drifted schema** (`docs/ops/DEPLOYMENT.md`, `docs/database/DATABASE_MIGRATION_RUNBOOK.md`). Migration `DRY_RUN` and `APPLY` need database access the pipeline does not have, and `APPLY` is a **separate, human-authorised, two-key operation** (`TAZZZO_MIGRATION_CONFIRM_DATABASE` and `TAZZZO_MIGRATION_CONFIRM_ENVIRONMENT`) done before the first deploy and before any deploy that adds a migration.

The deploy fails fast instead: if `/health/ready` reports `components.datastore=REFUSED` twice in a row, `scripts/uat-wait-healthy.sh` exits 3 with a message that the schema is not migrated, instead of waiting out the 5 minutes. Then the environment is left on the new (refusing) container: roll back with `rollback-uat` unless the migration is applied.

## Smoke test
`scripts/uat-smoke-test.sh <base-url>` (needs `curl` and `jq`; sends no credentials; prints no bodies). Routes were checked against `docs/api/v1/openapi.yaml`:
`GET /health/live`, `/health/ready`, `/v1/app-config`, `/v1/content/faqs`, `/v1/content/legal/terms` and `/privacy` (200, or a flat 404 when not published), `/v1/serviceability?pin=560001` (200 with a boolean `serviceable`, or a flat 400/429 JSON error), a deliberately bad `/v1/serviceability?pin=not-a-pin` (400 with `code`, `message` and `requestId` or `request_id`), the five security response headers, and `/api/v1/admin/me` unauthenticated (401). A 503 on a public read means the rate limiter or geo provider is unavailable and is reported as FAIL.

## Application settings UAT needs (instance side, infra repo)
- **`tazzzo.checkout.delivery-slot-required` must be `false`** (env `TAZZZO_CHECKOUT_DELIVERY_SLOT_REQUIRED=false`). The key is read in `OrderController` with default `false`; it is **not declared in `application.yml`**, so UAT only needs to not set it to `true`. UAT has no delivery-slot data, and `true` refuses every order without a slot.
- `TAZZZO_MIGRATION_MODE=VERIFY` for the API; `TAZZZO_BUILD_VERSION` set to the deployed SHA; secrets from the instance's secret store, never from this repo.

## Rollback
1. Find the previous SHA in the last deploy summary ("Previous SHA") or in `/tazzzo/uat/backend/previous-sha`.
2. Run workflow `rollback-uat` with `sha=<that 40-hex SHA>`. It checks the tag exists in ECR, runs `Action=rollback`, checks health and smoke, and writes a summary.
Migrations are append-only, so an image rollback does not revert the database.

## Cost
The build runs on the GitHub arm64 hosted runner, which is free for public repositories (a private repository bills minutes; set `UAT_RUNNER_LABEL` to the runner you want). ECR storage and the scan are the only AWS charges from this pipeline; the infra repo owns lifecycle rules. No recurring-cost resource is created by these workflows.

## Validation performed
`actionlint` 1.7.12 and `shellcheck` 0.11.0 on both workflows and all three scripts (both now run in `backend-ci.yml` as the `workflow-lint` job). The smoke script was exercised against a local mock HTTP server (all pass, and an unreachable URL fails); it was not run against the real backend.

## NOT VERIFIED
- Every AWS step: OIDC role assumption, ECR login/push/describe/scan, SSM send-command and polling, the SSM document's behaviour and its parameter names.
- The first run on `ubuntu-24.04-arm`: the Testcontainers suite has only been run on amd64 in `backend-ci.yml`; MongoDB 7 and Redis arm64 images are expected to work but are unproven.
- `ubuntu-24.04-arm` availability for this repository (free only if public).
- Trivy results (a fixable HIGH in the digest-pinned base image would fail the gate; the remedy is a deliberate Dockerfile base bump), and the SARIF upload (needs code scanning).
- `aquasecurity/trivy-action` is pinned to the commit of tag `v0.36.0` as seen by `git ls-remote` on 2026-10-12. That project's tags have been tampered with in the past: confirm the SHA against the upstream release before first use.
- The smoke script against a real backend, and the exact error codes on its negative checks (shape is asserted, not the code).
- The ECR scan wait assumes basic scan-on-push; enhanced (Inspector) scanning is not handled.
- That `components.datastore=REFUSED` appears for an unmigrated schema in a deployed container (taken from `docs/ops/HTTP_PLATFORM_BASELINE.md`).

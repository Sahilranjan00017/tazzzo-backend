# Platform hardening (PR-U)

| Control | Where | Proof |
|---|---|---|
| Defensive headers on every response, including 401/403/429/503 written by filters before routing | `SecurityHeadersFilter` | `SecurityHeadersIT` |
| No log statement passes a credential or contact value (token, Authorization, password, secret, phone, email, OTP, session id, refresh) | `LogSafetyGuardTest`, a static scan of every `log.*(...)` argument in `src/main/java` | the guard's own self-test, plus a reviewed allowlist with reasons |
| The dev-only LOGGING OTP provider cannot run where customers wait for a code | `OtpAuthConfig`: allowed only when `tazzzo.migration.environment` is unset, `local`, `test` or `dev` | `OtpLoggingProviderGuardTest` |
| No committed credentials | `scripts/secret-scan.sh` (CI job `secret-scan`): no third-party action, no network | prints path:line only, never the matched text; fixtures are allowlisted by exact value |
| Dependency updates are proposed weekly | `.github/dependabot.yml` (Maven and GitHub Actions) | each update is an ordinary PR that must pass CI; nothing auto-merges |

**Headers.** The service serves JSON only, so the header policy is maximal:

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `no-referrer` |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` (browsers ignore it over plain HTTP) |
| `Cross-Origin-Resource-Policy` | `same-site` |
| `Permissions-Policy` | every powerful feature off |

No `Server` or `X-Powered-By` header is sent.

**Already covered elsewhere** (not repeated here):
- #55: correlation-id validation against log injection, request-body limits, error sanitisation, CORS, health probes.
- #69: customer rate limits.
- The datastore contract: TLS, credentials, least-privilege roles, proxy refusal.

**Merge note.** #55 adds `PlatformFilterOrderTest`, which pins the filter chain. When both PRs are merged, add `SecurityHeadersFilter` (order `HIGHEST_PRECEDENCE`) to it. #65 edits `OtpAuthConfig` (HTTP provider mode) and needs a trivial rebase on top of the environment guard.

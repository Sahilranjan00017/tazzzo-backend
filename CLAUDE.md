# CLAUDE.md — tazzzo-backend engineering rules

Guidance for Claude Code (and any AI assistant) working in this repository.

## Stack
- **Java 21** (Temurin 21). The shell may default to a newer JDK — always build/test with Java 21.
- **Spring Boot 3.3.5** services.
- **Maven wrapper** (`./mvnw`) only — do not rely on a globally installed `mvn`.

## Repository layout
- `services/<name>/` — one independently deployable Spring Boot service per directory.
  - `services/catalog-service/` — the catalogue backend (MongoDB + Redis). Migrated, history-preserving, verified.

## Working method
1. **Use CodeGraph first** for navigation and impact analysis before scanning the tree by hand. Ask the graph "who calls this / what does this depend on" instead of re-reading whole packages.
2. **Inspect before editing.** Read the actual source of every file you intend to change — do not edit from memory or assumption.
3. **Smallest safe change.** Prefer the minimal diff that solves the problem. Do not refactor, rename, or reformat unrelated code.
4. **Run relevant tests after changes** (`./mvnw test` scoped to the affected service). Run the **full suite before merge** when the change is non-trivial or crosses module boundaries.
5. **Preserve API contracts** (HTTP routes, request/response shapes, event schemas) unless a change is explicitly approved.
6. **Update progress documentation** (`docs/ENGINEERING_STATUS.md`) for major completed work.

## Guardrails
- **No secrets in source or logs.** Configuration secrets come from environment/managed stores; never hardcode or print them.
- **No direct production changes.** No deploys, no infrastructure mutations from here.
- **No force push. Ever.**
- **No commit or push unless explicitly requested.**
- **Do not change catalog business logic** without explicit approval.
- Never modify the legacy safety repositories (`tazzzo-catalog-service`, `tazzzo-stories-backend`).

## Build / test quick reference
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd services/catalog-service
./mvnw -version          # must report Java 21
./mvnw clean test        # Testcontainers starts MongoDB 7 + Redis; Docker must be running
```

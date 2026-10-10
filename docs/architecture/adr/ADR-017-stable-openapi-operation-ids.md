# ADR-017: Stable, explicit OpenAPI operationIds

- **Status:** Accepted · **Date:** 2026-10-09

## Context
The generated admin/internal contract (`services/catalog-service/docs/openapi.json`) is consumed by generated clients (CMS, storefront, importer). springdoc names an operation after its handler method and, on a collision, appends a numeric suffix in registration order (`get_1` ... `get_13`, `list_1` ... `list_10`). Adding a controller or an overload therefore silently renumbered existing ids and broke clients. Before this change 46 of 134 operations carried such a suffix and most others were bare method names (`get`, `list`) that were one new controller away from becoming suffixed.

## Decision
`StableOperationIds` (a springdoc `OperationCustomizer`, package `com.tazzzo.catalog.api`) sets every operationId from code alone:

- `lowerCamel(ControllerSimpleName without "Controller") + CapitalisedMethodName`, e.g. `ProductController.create` becomes `productCreate`, `ServiceAreaAdminController.get` becomes `serviceAreaAdminGet`.
- Overloads (same controller class, same method name) get a suffix of the HTTP method, the path words (`{x}` becomes `ByX`) and the consumed media subtype, e.g. `...PostApiV1...Csv`. Never registration order.
- A handler with an explicit `@Operation(operationId = ...)` is left untouched. Explicit ids from earlier PRs (the import-job ids) are preserved, and `listStock` is now pinned explicitly because its published value is a plain method name that the scheme would otherwise rename.

`OperationIdContractIT` fails the build if ids are not unique, match `_\d+$`, do not match `^[a-z][A-Za-z0-9]*$`, or a pinned id disappears. `StableOperationIdsTest` proves the result is independent of handler registration order.

## Alternatives considered
- Annotate every handler with an explicit id: largest diff, easy to forget on new endpoints.
- Keep unique bare names and only rename suffixed ones: a new controller could still silently rename an existing bare id.

## Consequences
New endpoints get a stable id automatically; to choose a different name, set an explicit `@Operation(operationId)` and add it to the pinned list in `OperationIdContractIT`. Renaming a controller class or handler method renames its id, so treat that as a contract change.

## Compatibility / migration implications
Paths, methods and schemas are unchanged; only operationIds change. 122 of 134 ids were renamed once (mapping in the PR description); wire behaviour is unaffected, but generated client method names change and clients must be regenerated.

# Bounded batch product read

`GET /v1/products:batch?ids=TZP-1,TZP-2,...` returns the product card `GET /v1/products/{id}` would answer for each id, in one call. It exists so a
content rail (up to 20 `product:<id>` links) is one request instead of 20. Contract: `docs/api/v1/openapi.yaml`, operation `getProductsBatch`.

## Behaviour

- **Same answer as the single-id read.** Each `items[i]` is that id's card (same eligibility predicate, release-scoped vertical reachability,
  projection-freshness rule, current-price overlay and PIN enrichment) minus the detail-only gallery and attributes. No field is added.
- **Order and duplicates.** `items` and `missing` follow request order; a repeated id counts once, at its first position.
- **Missing, with no reason.** Unknown, draft, unlisted, discontinued, unconfirmed, holding-vertical, bundle, not reachable in the release, and
  merged ids are all listed in `missing` and look identical (same body shape, same reads, same charge): the call is not an existence oracle.
  A merged id is not followed to its survivor (the single-id read does that); ask for the survivor.
- **Cache.** `Cache-Control: private, no-store`, no ETag, as the single-id read.
- **Auth.** None. Public like `/v1/products/{id}`; a verified `X-Tazzzo-Caller` pair is charged to the caller bucket, a wrong one is an ordinary client.

## Bounds

| Bound | Value | Refusal |
|---|---|---|
| ids per request (duplicates count) | `tazzzo.commerce.product-batch.max-ids`, default and hard max 50 | 400 `INVALID_REQUEST` |
| id grammar | `ProductIds` (`^TZP-[A-Za-z0-9-]{1,40}$`), exact case, no trimming | 400 |
| `ids` parameter | exactly once, non-empty, at most 50 x 45 - 1 = 2249 chars at the default | 400 |
| raw query string | at most 2866 chars at the default (ids at their longest with `%2C` separators, plus 512 for `release`/`pin`) | 400 |
| response | at most 50 cards (about 1 KB each) | n/a |

Any 400 is the fixed flat envelope (`code`, `message: "invalid request"`, `requestId`, `retryable: false`), never echoes the input, is refused
BEFORE the release is resolved or anything is charged, and reads nothing. A query the container cannot decode (`%ZZ`, bad UTF-8) is answered by
`MalformedQueryFilter` with the same envelope. An out-of-range `max-ids` fails startup.

## Cost and reads

- **Admission:** `1 + distinct ids` units (2..51), charged once before any read, like a list's `1 + page_size`. Refused: 429 + `Retry-After`.
  **Capacity rule:** every bucket (`ip`, `installation`, `caller`) must hold at least `max-ids + 1` = 51 units, or a full batch can never be admitted.
  The caller bucket default (20000) is fine; size the IP and installation buckets against it.
- **Reads, independent of the number of ids:** one `products` find (`_id` `$in`, `_id_` index), one `product_card_base` find (`sku_id` index), one
  `price_current` find, one serviceability resolution and one inventory batch (only with a serviceable `pin`), plus one snapshot node read per distinct
  vertical for reachability. No collection scan; asserted from the server profiler and `explain` in `CommerceProductBatchIT`.
- **Metrics:** route label `commerce_products_batch` on the existing consumer meters (requests, duration, rate_limit.cost, trusted-caller admissions).

## Not verified here

Production bucket sizes, and the production latency of a 50-id batch with a serviceable PIN (no load test was run).

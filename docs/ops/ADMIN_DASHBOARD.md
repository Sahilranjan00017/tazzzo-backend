# Admin operations dashboard

`GET /api/v1/admin/dashboard/summary`: read access per the admin access policy. No parameters, `Cache-Control: no-store`, no personal data.

| Section | Counts | How it stays bounded |
|---|---|---|
| `orders` | open CONFIRMED / OUT_FOR_DELIVERY; last-24h CONFIRMED / OUT_FOR_DELIVERY / DELIVERED / CANCELLED | index `order_by_status_recent` (status, createdAt) |
| `inventory` | out of stock (active, on_hand ≤ 0); low stock (active, 0 < on_hand ≤ low_stock_threshold) | cap + time limit (field comparison is not indexable) |
| `catalog` | products total (collection estimate), active, draft | estimate is O(1); others cap + time limit |
| `serviceability` | service areas total (estimate), active | estimate O(1); cap + time limit |
| `support` | OPEN, IN_PROGRESS | index `support_by_status_recent` |
| `notifications` | PENDING, FAILED | index `notification_due` |

- **Bounds.** Every count is `{value, capped}`. It stops at **10,000** (`capped: true` means "at least 10,000") and has a **2 s** server-side time limit. A timeout or datastore failure is a 503 `SERVICE_UNAVAILABLE`, never a partial or invented number.
- **Recent audit activity** is served, paged and filtered, by the existing `GET /api/v1/admin/audit-events?limit=…`. It is not duplicated here.
- **Why this exists although the CMS home is a placeholder today:** CMS business modules are next, and an operations home needs these counts. Each one is a single bounded query, not a client-side scan of list endpoints.

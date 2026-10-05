# Admin roles and access (INTERNAL surface, `/api/**`)

Every request is authenticated first (shared service token, or a per-person Google OIDC token resolved against the backend
allowlist), then authorised by `AdminAccessPolicy` — the single place that decides.

| Role | Held by | Grants |
|---|---|---|
| `reader` | human or shared token | read the general INTERNAL surface (GET) |
| `cms-writer` | human or shared token | read + write the general INTERNAL surface (catalogue, taxonomy, pricing, stock, media, serviceability, delivery windows) |
| `audit-reader` | human only | the admin audit-read API and `/api/v1/admin/me`, nothing else |
| `order-ops` | **human only** | read **and** write `/api/v1/admin/orders/**` (customer personal data); read-only on `/api/v1/admin/support/**`; `/api/v1/admin/me` |
| `support-agent` | **human only** | read **and** write `/api/v1/admin/support/**`; read-only on `/api/v1/admin/orders/**`; `/api/v1/admin/me` |

Rules that hold (tested by `AdminAccessPolicyTest` and `AdminStaffRbacIT`):
- The staff namespaces (`/api/v1/admin/orders`, `/api/v1/admin/support`) hold customer personal data. **Only** `order-ops` / `support-agent` reach them. `cms-writer`, `reader`, `audit-reader` and the shared service tokens do **not**.
- A staff role confers **nothing** on the catalogue surface (no read, no write, no audit read) — only `/api/v1/admin/me`.
- A staff role is honoured only for a `HUMAN_ADMIN`; the shared tokens are fixed to `reader`/`cms-writer` and never carry one.
- Namespace matching is exact on the segment boundary (`/api/v1/admin/orders2` is not the orders namespace; case matters; decorated/encoded variants are refused earlier by the surface classifier).
- Roles come from the backend allowlist (`tazzzo.admin.users[i].roles`), never from token claims. Unknown role names fail startup.

Granting a staff role: add it to the person's `roles` in the allowlist (comma list). Revoking: remove it or set `enabled=false`. The audit ledger records `actor.id` (`google:<sub>`) for every staff write.

The general INTERNAL surface still uses the coarse model (`cms-writer` writes everything on it, including pricing/stock/delivery). Splitting those into narrower roles is a deliberate follow-up: it would change the shared `cms-writer` service token's existing reach and needs an owner decision on which automation keeps broad access.

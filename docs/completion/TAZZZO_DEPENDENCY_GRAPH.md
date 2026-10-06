# TAZZZO — Dependency graph

```
P0 PR#93 review ──► CEO merge approval ──► backend main advances
P1 audit/trackers (this set)
   │
   ├─► P3 MEDIA-STORAGE (local/test adapter: no approval) ──► [paid S3+CDN approval] ──► live media
   │        │
   │        ├─► P4 MEDIA-API/MEDIA-CMS ──► P6 APP media ──┐
   │        │                                              ├─► P12 E2E TEST 3/4/5
   │        └─► P5 CHANNEL+BANNERS (needs channel authorisation) ──► P6 APP banners, P7 WEBSITE ──► TEST 6–9
   │
   ├─► P2 CMS stack merge (owner approval) ──► Dockerfile ──► P13 staging (ALB/ECS/Valkey/Atlas: paid) ──► TEST 13/14/15
   │
   ├─► P8 CATALOGUE-SCALE: harness ──► async imports ──► data-model entities ──► admin list scale
   │        │                                   │
   │        │                                   └─► TEST 10/17
   │        └─► P9 SEARCH-SCALE (needs harness numbers; Atlas Search vs OpenSearch decision)
   │
   └─► P11 platform upgrade (Boot 4.x) — independent of features; must precede production
```

External gates (see `TAZZZO_EXTERNAL_BLOCKERS.md`): 5,000-SKU master dataset (TEST 10, launch validation), paid S3/CDN, paid staging runtime, channel authorisation, CMS merge order, Google OIDC client for staging, Atlas cluster.

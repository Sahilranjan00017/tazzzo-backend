# Media object storage (S3-compatible)

Product images never pass through the service. The flow is: the CMS asks the backend for an upload target → the backend
answers a short-lived **presigned PUT** for one server-generated key → the client uploads straight to the object store →
the CMS references the key in a media set → the backend **verifies the stored object** (exists, size within the ceiling,
magic bytes match the declared type) before the key can enter the set → the public URL is derived at read time by
`MediaUrlResolver` from `tazzzo.media.public-base-url` (the CDN, per CDN-HOST-1 `https://cdn.tazzzo.com` once operational).

## Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `tazzzo.media.storage.provider` | `TAZZZO_MEDIA_STORAGE_PROVIDER` | `disabled` | `disabled`: uploads answer 503 `MEDIA_STORAGE_NOT_CONFIGURED`, media sets stay metadata-only. `s3`: the adapter below. |
| `tazzzo.media.storage.s3.bucket` | `TAZZZO_MEDIA_S3_BUCKET` | — | required for `s3` |
| `tazzzo.media.storage.s3.region` | `TAZZZO_MEDIA_S3_REGION` | `ap-south-1` | AWS-REGION-1 |
| `tazzzo.media.storage.s3.endpoint` | `TAZZZO_MEDIA_S3_ENDPOINT` | unset | S3-compatible store only (local/test). Unset for AWS. Must be `http(s)://` with no credentials in the URL; `http://` only for loopback, `host.docker.internal` or a single-label host (a compose service name). |
| `tazzzo.media.storage.s3.path-style` | `TAZZZO_MEDIA_S3_PATH_STYLE` | `false` | `true` for a local S3-compatible store |
| `tazzzo.media.storage.s3.presign-ttl-seconds` | `TAZZZO_MEDIA_S3_PRESIGN_TTL_SECONDS` | `300` | 30..3600 |
| `tazzzo.media.storage.s3.access-key` / `secret-key` | `TAZZZO_MEDIA_S3_ACCESS_KEY` / `TAZZZO_MEDIA_S3_SECRET_KEY` | unset | **local store only.** Unset in AWS: the ECS task role is used (default credential chain). Never commit values. |
| `tazzzo.media.max-upload-bytes` | `TAZZZO_MEDIA_MAX_UPLOAD_BYTES` | 5 MiB | upload ceiling (1 B .. 50 MiB); enforced at presign (signed `Content-Length`) and again at verify time against the store's real size |
| `tazzzo.media.max-pixels` | `TAZZZO_MEDIA_MAX_PIXELS` | `50000000` (50 MP) | decompression-bomb bound: width x height read from the image header must not exceed it |
| `tazzzo.media.max-dimension` | `TAZZZO_MEDIA_MAX_DIMENSION` | `20000` | longest allowed single side in pixels (also the ceiling for a declared width/height) |
| `tazzzo.migration.environment` | `TAZZZO_MIGRATION_ENVIRONMENT` | unset | selects the storage-less behaviour below (unset/`local`/`test`/`dev` = lenient; anything else = fail closed) |

Startup logs `media_storage provider=s3 bucket=… region=… endpoint=… credentials=static|default-chain`; never a credential value.

## Verification pipeline (when a key is first referenced by a media set or CMS banner)
`PUT /api/v1/admin/media/{ownerType}/{ownerId}` (cms-writer only; reader 403, unknown product 404) runs, per NEWLY
referenced key (keys already in the set are not re-checked):
1. key prefix = `p/<ownerType>/<ownerId>/` (issued for this owner);
2. `HeadObject` + a ranged `GET bytes=0-65535` pinned by `If-Match` (at most 64 KiB is ever read; `inspect`);
3. real stored size within `1..max-upload-bytes` (the store's size, never the declared one);
4. magic bytes sniff to jpeg/png/webp (GIF, BMP, TIFF, SVG, HTML, polyglot prefixes are not images here: 422);
5. declared `contentType` (if any) = sniffed type; stored `Content-Type` (if any) = sniffed type; key extension
   (`.jpg`/`.jpeg`, `.png`, `.webp`, case-insensitive) = sniffed type;
6. header-only dimensions (PNG IHDR; JPEG SOFn scan confined to the first 64 KiB; WebP VP8/VP8L/VP8X) must parse
   (truncated or garbage headers that pass the magic bytes are 422), each side <= `max-dimension`, area <=
   `max-pixels`, and equal the declared `width`/`height` when those are supplied.
All refusals are 422 `INVALID_MEDIA` (no request shape changed). Nothing decodes pixels, so the check is O(64 KiB).
Content after a valid header (a PNG `tEXt` with markup, a JPEG with trailing HTML) is inert to an image-only store; it
is not scanned. Assumption to confirm at the CDN: images are served with their stored `image/*` type and
`X-Content-Type-Options: nosniff` (the application sets nosniff on its own responses; the CDN response-headers policy
must do the same for the bucket origin). Nothing in the pipeline compares an ETag to a checksum (a multipart ETag is not
an MD5); the ETag is used only as an opaque `If-Match` pin.

### No storage configured (`provider=disabled`)
References cannot be verified. In `tazzzo.migration.environment` unset/`local`/`test`/`dev` a NEW reference is accepted
unverified (startup logs `media_verification disabled ... accepted UNVERIFIED`). In any other environment it is refused
fail-closed: 503 `MEDIA_STORAGE_NOT_CONFIGURED`, nothing written. Clearing a set and re-saving keys already in it still
work. Uploads are 503 in every environment. (CMS banner image keys keep their existing no-storage behaviour.)

## Orphan objects (operational procedure, no job in the service)
An upload that is never referenced (abandoned, or refused at verification) stays in the bucket; its size is bounded by
the signed `Content-Length` and its count by cms-writer trust. The service holds no list/delete permission and exposes no
endpoint for this. Procedure, run by an operator with bucket access: (1) take the set of referenced keys from
`media_refs` (all `assets.asset_key`) and `content_blocks` image keys; (2) list objects under `p/` and `c/` older than a
grace period (>= 7 days, well above the 30 s..1 h presign TTL); (3) review the difference, then remove it; (4) never
touch a key present in step 1. A bucket lifecycle rule that aborts incomplete multipart uploads after 1 day is safe to
enable independently. Re-verification of already-referenced keys is not automatic: re-saving a set does not re-check
existing keys, so an object replaced out-of-band must be handled by a new upload and a new key.

## What the adapter guarantees, and what it does not
- The presigned URL is for **exactly one key** and **binds `Content-Type`, `Content-Length`** (the declared
  `sizeBytes`) **and `If-None-Match: *`** (`X-Amz-SignedHeaders=content-length;content-type;host;if-none-match`): a
  different key, type, a larger body, a dropped precondition, a tampered signature or an expired one is refused by any
  store that verifies SigV4 (403). AWS S3 does; so does Versity S3 Gateway, against which `S3SignatureEnforcementIT`
  proves every refusal locally. Adobe S3Mock (the flow-test store) verifies no signatures.
- **Write-once:** because of `If-None-Match: *`, the URL can only create the object; re-using it after the bytes exist
  is refused (412), so bytes that were verified and referenced can never be swapped under the same key. Replacing an
  image always means a new upload target (a new key) and a media-set write.
- `inspect` pins its ranged read to the ETag its HeadObject saw (`If-Match`), so an object changed between the two
  calls fails the read instead of mixing two objects' metadata and bytes.
- The stored `Content-Type` must equal the type the bytes sniff as (also when the asset declares no type), so an image
  is never delivered under another image type's label.
- Storage calls are bounded: 2 s per attempt, 5 s per call including SDK retries, then 503 `MEDIA_STORAGE_UNAVAILABLE`.
- The client must send the returned headers as given (one canonical spelling each, `Content-Length` equal to the body).
- A newly referenced key must have been issued for **that owner** (`p/<ownerType>/<ownerId>/…`): a key uploaded for one
  product cannot be attached to another (422), even though storage holds a valid image under it.
- The bytes are checked again at reference time by `MediaIngestVerifier` through `inspect` (HeadObject size + first 64
  bytes sniffed, then header dimensions parsed from up to 64 KiB): a non-image object of the declared size can never enter a media set. It occupies the bucket until an
  unreferenced-object reaper exists (not yet; see "Not in this PR").
- A store that cannot be reached or refuses the request answers 503 `MEDIA_STORAGE_UNAVAILABLE` (one WARN line with the
  failure's class name, no stack trace, no key/host/credential), distinct from 503 `MEDIA_STORAGE_NOT_CONFIGURED`
  (provider disabled) and from 422 (object not uploaded).
- The service reads at most 64 KiB of any object and never serves bytes.

## AWS resources required for `provider=s3` (not yet provisioned; tracked as blocker B3 in the completion trackers)
1. Private bucket in ap-south-1, block public access, SSE-S3, versioning optional; **CORS**: allow `PUT` from the CMS
   origin with `Content-Type` and `If-None-Match` (the bucket-wide CORS rule covers `p/` and `c/`); **lifecycle**: abort incomplete multipart after 1 day, and expire objects under
   `p/` or `c/` that are not referenced (the reference reaper is a later PR; until then, expire nothing — an unreferenced
   object is bounded in size by the signed `Content-Length` and in count by cms-writer trust).
2. Task-role policy (least privilege): `s3:PutObject` (only via presign, so the role needs it) and `s3:GetObject` (it
   also authorises HeadObject; there is no `s3:HeadObject` action) on `arn:aws:s3:::<bucket>/p/*` **and** `arn:aws:s3:::<bucket>/c/*` (product media and CMS content imagery, e.g.
   banners under `c/home/`), plus `s3:ListBucket` on `arn:aws:s3:::<bucket>` (unconditioned: the bucket is dedicated to media, and whether S3's implicit 404-vs-403 check honours an `s3:prefix` condition is undocumented). **The ListBucket grant is required**: without it S3
   answers HeadObject on a missing key with 403, which the service must treat as an outage (503
   `MEDIA_STORAGE_UNAVAILABLE`) rather than "not uploaded yet" (422). Nothing else (no DeleteObject).
   Presigned PUTs are write-once: the signature binds `If-None-Match: *`, so an existing key is never overwritten (S3
   answers 412); CORS must therefore also allow the `If-None-Match` request header.
3. CloudFront distribution with the bucket as origin (OAC), serving `https://cdn.tazzzo.com`; `Cache-Control` set by a
   response-headers policy. Do not repoint `public-base-url` until the hostname is operational (CDN-HOST-1 rule).

## Local development with an S3-compatible store
MinIO's public container images are no longer pullable (verified 2026-10-07), so local development uses Adobe S3Mock,
the same store the tests use. It ignores credentials (any non-empty pair works) and verifies no signatures:
```
docker run -p 9090:9090 -e initialBuckets=tazzzo-media-dev adobe/s3mock:3.11.0
TAZZZO_MEDIA_STORAGE_PROVIDER=s3 TAZZZO_MEDIA_S3_BUCKET=tazzzo-media-dev TAZZZO_MEDIA_S3_ENDPOINT=http://localhost:9090 \
TAZZZO_MEDIA_S3_PATH_STYLE=true TAZZZO_MEDIA_S3_ACCESS_KEY=local TAZZZO_MEDIA_S3_SECRET_KEY=local ./mvnw spring-boot:run
```

## Tests
- `S3MediaStorageIT`: adapter against S3Mock (real presigned PUT, inspect size/type/magic bytes, ranged head on a
  large object, empty object, unreachable store → `MediaStorageFailure`, unsafe keys, bad config) plus the
  signature-contract check on the presigned URL (signed headers, TTL, no secret).
- `S3SignatureEnforcementIT`: Versity S3 Gateway v1.8.0 (SigV4- and precondition-enforcing, pinned by digest): wrong
  type, no type, larger body, another key, a tampered signature and a dropped `If-None-Match` are refused (403); the
  signed request succeeds; stored type is the signed one; a second PUT on the same URL is refused (412) and the stored
  bytes are unchanged; an `If-Match` read after the object changed fails (412). (Scality CloudServer, used before,
  verifies signatures but ignores `If-None-Match`, so it cannot prove write-once.)
- `MediaUploadEndToEndIT`: the whole admin flow over HTTP with `provider=s3` against S3Mock (upload target → real PUT →
  media set verified → readable), and the rejections (not uploaded, wrong declared type, another owner's key, policy,
  unknown product, reader role).
- `MediaStorageOutageIT`: an unreachable store during verification → 503 `MEDIA_STORAGE_UNAVAILABLE`, no stack trace.
- `MediaAdminNoStorageIT`: the default deployment still refuses uploads with 503.

## Not in this PR
Image variants/thumbnails, deduplication, unreferenced-object reaper, bulk image mapping, CDN cache invalidation,
cost tracking, and the Terraform for the bucket/CloudFront (needs budget approval). The SDK ships 31 jars (30
`software.amazon.awssdk:*` + `eventstream`); the Apache 4, Apache 5 and Netty HTTP clients are excluded, so no
`httpclient`/`httpcore` jar ships (verified with `dependency:build-classpath`).

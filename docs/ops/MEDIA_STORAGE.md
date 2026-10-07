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
| `tazzzo.media.max-upload-bytes` | — | 5 MiB | upload ceiling (1 B .. 50 MiB) |

Startup logs `media_storage provider=s3 bucket=… region=… endpoint=… credentials=static|default-chain`; never a credential value.

## What the adapter guarantees, and what it does not
- The presigned URL is for **exactly one key** and **binds `Content-Type` and `Content-Length`** (the declared
  `sizeBytes`; `X-Amz-SignedHeaders=content-length;content-type;host`, pinned by `S3MediaStorageIT`): a different key,
  type, a larger body, a tampered signature or an expired one is refused by any store that verifies SigV4. AWS S3 does;
  so does Scality CloudServer, against which `S3SignatureEnforcementIT` proves every refusal locally. Adobe S3Mock (the
  flow-test store) verifies no signatures.
- The client must send the returned headers as given (one canonical spelling each, `Content-Length` equal to the body).
- A newly referenced key must have been issued for **that owner** (`p/<ownerType>/<ownerId>/…`): a key uploaded for one
  product cannot be attached to another (422), even though storage holds a valid image under it.
- The bytes are checked again at reference time by `MediaIngestVerifier` through `inspect` (HeadObject size + first 64
  bytes sniffed): a non-image object of the declared size can never enter a media set. It occupies the bucket until an
  unreferenced-object reaper exists (not yet; see "Not in this PR").
- A store that cannot be reached or refuses the request answers 503 `MEDIA_STORAGE_UNAVAILABLE` (one WARN line with the
  failure's class name, no stack trace, no key/host/credential), distinct from 503 `MEDIA_STORAGE_NOT_CONFIGURED`
  (provider disabled) and from 422 (object not uploaded).
- The service reads at most 64 bytes of any object and never serves bytes.

## AWS resources required for `provider=s3` (not yet provisioned; tracked as blocker B3 in the completion trackers)
1. Private bucket in ap-south-1, block public access, SSE-S3, versioning optional; **CORS**: allow `PUT` from the CMS
   origin with `Content-Type`; **lifecycle**: abort incomplete multipart after 1 day, and expire objects under
   `p/` that are not referenced (the reference reaper is a later PR; until then, expire nothing — an unreferenced
   object is bounded in size by the signed `Content-Length` and in count by cms-writer trust).
2. Task-role policy (least privilege): `s3:PutObject` (only via presign, so the role needs it), `s3:GetObject`,
   `s3:HeadObject` on `arn:aws:s3:::<bucket>/p/*`; nothing else, no `ListBucket`.
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
- `S3SignatureEnforcementIT`: Scality CloudServer (SigV4-verifying, pinned by digest): wrong type, no type, larger body,
  another key and a tampered signature are refused (403); the signed request succeeds; stored type is the signed one.
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

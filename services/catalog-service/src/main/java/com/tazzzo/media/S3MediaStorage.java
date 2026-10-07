package com.tazzzo.media;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MediaStorage} over any S3-compatible object store (AWS S3 in production; MinIO in tests and locally).
 *
 * <p>The service never touches image bytes. {@link #createUpload} returns a presigned {@code PUT} for exactly one
 * server-generated key whose signature binds the {@code Content-Type} AND {@code Content-Length} headers: a client that
 * uploads a different type, a different size, a different key, or after {@code expiresAt} is refused by the store
 * itself (any SigV4-verifying store, AWS S3 included). The bytes are checked again when the key is REFERENCED, by
 * {@link MediaIngestVerifier} through {@link #inspect}, which reads the object's real size, stored type and first
 * bytes, so a non-image upload of the declared size can never enter a media set (it occupies the bucket until an
 * unreferenced-object reaper removes it; none exists yet).
 *
 * <p>Credentials are never part of this class: the clients are built by {@code MediaStorageConfig} from the task
 * role (default provider chain) or, for a local store, from explicit configuration.
 */
public final class S3MediaStorage implements MediaStorage {

    /** Enough leading bytes for {@link MediaSniffer} (JPEG 3, PNG 8, WebP 12). */
    static final int HEAD_BYTES = 64;
    static final Duration MIN_TTL = Duration.ofSeconds(30);
    static final Duration MAX_TTL = Duration.ofHours(1);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;

    public S3MediaStorage(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl) {
        if (s3 == null || presigner == null) {
            throw new IllegalArgumentException("s3 client and presigner are required");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket is required");
        }
        if (presignTtl == null || presignTtl.compareTo(MIN_TTL) < 0 || presignTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("presign ttl must be between " + MIN_TTL + " and " + MAX_TTL);
        }
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public UploadTarget createUpload(String assetKey, String contentType, long sizeBytes) {
        if (!MediaAsset.isSafeKey(assetKey)) {
            throw new InvalidMediaException("unsafe storage key");   // defence in depth: the policy generates keys
        }
        if (contentType == null || contentType.isBlank()) {
            throw new InvalidMediaException("contentType is required");
        }
        if (sizeBytes < 1) {
            throw new InvalidMediaException("sizeBytes must be positive");
        }
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(assetKey).contentType(contentType)
                .contentLength(sizeBytes).build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl).putObjectRequest(put).build());
        // the client must send exactly the signed headers (host excluded: the URL carries it); one canonical spelling each
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> h : presigned.signedHeaders().entrySet()) {
            String name = h.getKey().toLowerCase(java.util.Locale.ROOT);
            if (h.getValue().isEmpty() || name.equals("host")) continue;
            headers.put(canonical(name), h.getValue().get(0));
        }
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", Long.toString(sizeBytes));
        return new UploadTarget("PUT", presigned.url().toString(), headers, presigned.expiration());
    }

    /** {@code content-type} -> {@code Content-Type}; {@code x-amz-foo-bar} -> {@code X-Amz-Foo-Bar}. */
    static String canonical(String lower) {
        StringBuilder out = new StringBuilder(lower.length());
        boolean up = true;
        for (char c : lower.toCharArray()) {
            out.append(up ? Character.toUpperCase(c) : c);
            up = c == '-';
        }
        return out.toString();
    }

    @Override
    public Optional<StoredObject> inspect(String assetKey) {
        if (!MediaAsset.isSafeKey(assetKey)) {
            return Optional.empty();
        }
        try {
            HeadObjectResponse head;
            try {
                head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(assetKey).build());
            } catch (NoSuchKeyException e) {
                return Optional.empty();
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return Optional.empty();   // a store that answers a bare 404 (NoSuchKey is the usual form)
                }
                throw e;
            }
            long size = head.contentLength() == null ? 0 : head.contentLength();
            byte[] first = new byte[0];
            if (size > 0) {
                long last = Math.min(size, HEAD_BYTES) - 1;
                ResponseBytes<GetObjectResponse> bytes = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(assetKey)
                        .range("bytes=0-" + last).build(), ResponseTransformer.toBytes());
                first = bytes.asByteArray();
            }
            return Optional.of(new StoredObject(size, head.contentType(), first));
        } catch (SdkException e) {
            // unreachable store, wrong credentials, forbidden bucket: an outage, never "not uploaded" and never a 500
            throw new MediaStorageFailure(e.getClass().getSimpleName());
        }
    }
}

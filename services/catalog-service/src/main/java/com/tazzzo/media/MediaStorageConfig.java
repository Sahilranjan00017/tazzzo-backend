package com.tazzzo.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default (disabled) storage, the upload policy and the verifier. A provider module overrides the storage bean. */
@Configuration
class MediaStorageConfig {

    @Bean
    @ConditionalOnMissingBean(MediaStorage.class)
    MediaStorage disabledMediaStorage() {
        return new DisabledMediaStorage();
    }

    @Bean
    MediaUploadPolicy mediaUploadPolicy(@Value("${tazzzo.media.max-upload-bytes:" + MediaUploadPolicy.DEFAULT_MAX_BYTES + "}") long maxBytes) {
        return new MediaUploadPolicy(maxBytes);
    }

    @Bean
    MediaIngestVerifier mediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy) {
        return new MediaIngestVerifier(storage, policy);
    }
}

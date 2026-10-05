package com.tazzzo.media;

import java.util.Optional;

/**
 * Content-type detection from the first bytes of a stored object: the check the claimed {@code contentType} of a
 * {@link MediaAsset} never had. Closed world, matching {@link MediaAsset#ALLOWED_CONTENT_TYPES}; anything else is
 * "unknown" and therefore refused.
 */
public final class MediaSniffer {

    private MediaSniffer() { }

    public static Optional<String> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (startsWith(head, png)) {
            return Optional.of("image/png");
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

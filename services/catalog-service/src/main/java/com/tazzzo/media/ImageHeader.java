package com.tazzzo.media;

/**
 * Header-only pixel-dimension parsing for the three allowed image types, over a BOUNDED prefix of the object (never a
 * decode, never the whole body). It exists so the declared width/height can be checked against the real image and so a
 * decompression bomb (a tiny file that declares billions of pixels) is refused before any consumer or CDN transform
 * decodes it. A header that passes the magic-byte sniff but is truncated or malformed is refused here.
 */
public final class ImageHeader {

    /** Prefix length the verifier asks storage for; the JPEG scan for a SOF marker is confined to it. */
    public static final int MAX_SCAN_BYTES = 64 * 1024;

    public record Dimensions(long width, long height) {
        public long pixels() {
            return width * height;
        }
    }

    private ImageHeader() { }

    /** @throws InvalidMediaException the header is truncated, malformed, or declares a zero dimension */
    public static Dimensions read(String contentType, byte[] head) {
        Dimensions d = switch (contentType) {
            case "image/png" -> png(head);
            case "image/jpeg" -> jpeg(head);
            case "image/webp" -> webp(head);
            default -> throw new InvalidMediaException("unsupported contentType");
        };
        if (d.width() < 1 || d.height() < 1) {
            throw new InvalidMediaException("image declares a zero dimension");
        }
        return d;
    }

    private static Dimensions png(byte[] b) {
        // signature(8) + IHDR chunk: length(4)=13, "IHDR", width(4), height(4)
        if (b.length < 24 || u32(b, 8) != 13 || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            throw new InvalidMediaException("PNG header is truncated or malformed");
        }
        return new Dimensions(u32(b, 16), u32(b, 20));
    }

    private static Dimensions jpeg(byte[] b) {
        int n = Math.min(b.length, MAX_SCAN_BYTES);
        int i = 2;   // after SOI
        while (i < n) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new InvalidMediaException("JPEG marker stream is malformed");
            }
            while (i < n && (b[i] & 0xFF) == 0xFF) i++;   // fill bytes
            if (i >= n) break;
            int marker = b[i++] & 0xFF;
            if (marker == 0x00) {
                throw new InvalidMediaException("JPEG marker stream is malformed");
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue;   // standalone markers carry no length
            }
            if (marker == 0xD9 || marker == 0xDA) {
                throw new InvalidMediaException("JPEG has no frame header before image data");
            }
            if (i + 2 > n) break;
            int len = u16(b, i);
            if (len < 2) {
                throw new InvalidMediaException("JPEG segment length is malformed");
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (len < 8 || i + 7 > n) {
                    throw new InvalidMediaException("JPEG frame header is truncated");
                }
                return new Dimensions(u16(b, i + 5), u16(b, i + 3));   // precision(1) height(2) width(2)
            }
            i += len;
        }
        throw new InvalidMediaException("JPEG frame header not found within the first " + MAX_SCAN_BYTES + " bytes");
    }

    private static Dimensions webp(byte[] b) {
        if (b.length < 30) {
            throw new InvalidMediaException("WebP header is truncated");
        }
        String fourcc = new String(b, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (fourcc) {
            case "VP8 " -> {   // lossy: frame tag(3), start code 9D 01 2A, 14-bit width, 14-bit height (scale bits dropped)
                if ((b[23] & 0xFF) != 0x9D || b[24] != 0x01 || b[25] != 0x2A) {
                    throw new InvalidMediaException("WebP VP8 frame header is malformed");
                }
                return new Dimensions(u16le(b, 26) & 0x3FFF, u16le(b, 28) & 0x3FFF);
            }
            case "VP8L" -> {   // lossless: signature 0x2F, then 14-bit (width-1) and 14-bit (height-1), little-endian bit order
                if ((b[20] & 0xFF) != 0x2F) {
                    throw new InvalidMediaException("WebP VP8L header is malformed");
                }
                long bits = (b[21] & 0xFFL) | (b[22] & 0xFFL) << 8 | (b[23] & 0xFFL) << 16 | (b[24] & 0xFFL) << 24;
                return new Dimensions((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
            }
            case "VP8X" -> {   // extended: flags(4), canvas width-1 (24-bit LE), canvas height-1 (24-bit LE)
                long w = (b[24] & 0xFFL) | (b[25] & 0xFFL) << 8 | (b[26] & 0xFFL) << 16;
                long h = (b[27] & 0xFFL) | (b[28] & 0xFFL) << 8 | (b[29] & 0xFFL) << 16;
                return new Dimensions(w + 1, h + 1);
            }
            default -> throw new InvalidMediaException("WebP chunk type is not supported");
        }
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) << 24 | (b[o + 1] & 0xFFL) << 16 | (b[o + 2] & 0xFFL) << 8 | (b[o + 3] & 0xFFL);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF);
    }

    private static int u16le(byte[] b, int o) {
        return (b[o + 1] & 0xFF) << 8 | (b[o] & 0xFF);
    }
}

package com.tazzzo.media;

import java.io.ByteArrayOutputStream;

/** Programmatically built, header-only synthetic images (a few dozen bytes): enough for sniffing and dimension parsing. */
public final class TestImages {

    private TestImages() { }

    public static byte[] png(long w, long h) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        o.writeBytes(be32(13));
        o.writeBytes("IHDR".getBytes());
        o.writeBytes(be32(w));
        o.writeBytes(be32(h));
        o.writeBytes(new byte[]{8, 2, 0, 0, 0});
        return o.toByteArray();
    }

    public static byte[] jpeg(int w, int h) {
        return jpegWithApp(w, h, 0);
    }

    /** SOI, {@code appSegments} APPn segments of 100 bytes each, then SOF0. */
    public static byte[] jpegWithApp(int w, int h, int appSegments) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        for (int i = 0; i < appSegments; i++) {
            o.writeBytes(new byte[]{(byte) 0xFF, (byte) (0xE0 + (i % 16)), 0, 100});
            o.writeBytes(new byte[98]);
        }
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0, 11, 8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 1, 1, 0x11, 0});
        return o.toByteArray();
    }

    /** Lossy WebP (VP8 chunk). */
    public static byte[] webpLossy(int w, int h) {
        byte[] b = riff("VP8 ");
        b[20] = 0x30; b[21] = 0; b[22] = 0;
        b[23] = (byte) 0x9D; b[24] = 0x01; b[25] = 0x2A;
        b[26] = (byte) w; b[27] = (byte) (w >> 8); b[28] = (byte) h; b[29] = (byte) (h >> 8);
        return b;
    }

    public static byte[] webpLossless(int w, int h) {
        byte[] b = riff("VP8L");
        long bits = ((long) (w - 1) & 0x3FFF) | (((long) (h - 1) & 0x3FFF) << 14);
        b[20] = 0x2F;
        for (int i = 0; i < 4; i++) b[21 + i] = (byte) (bits >> (8 * i));
        return b;
    }

    public static byte[] webpExtended(int w, int h) {
        byte[] b = riff("VP8X");
        for (int i = 0; i < 3; i++) {
            b[24 + i] = (byte) ((w - 1) >> (8 * i));
            b[27 + i] = (byte) ((h - 1) >> (8 * i));
        }
        return b;
    }

    private static byte[] riff(String fourcc) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
        b[4] = 32;
        System.arraycopy("WEBP".getBytes(), 0, b, 8, 4);
        System.arraycopy(fourcc.getBytes(), 0, b, 12, 4);
        b[16] = 20;
        return b;
    }

    private static byte[] be32(long v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }
}

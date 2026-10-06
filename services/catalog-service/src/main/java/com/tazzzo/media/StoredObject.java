package com.tazzzo.media;

/** What the storage reports about an object: its size, the content type it stores, and its first bytes (for sniffing). */
public record StoredObject(long sizeBytes, String contentType, byte[] head) {

    public StoredObject {
        head = head == null ? new byte[0] : head.clone();
    }

    @Override
    public byte[] head() {
        return head.clone();
    }
}

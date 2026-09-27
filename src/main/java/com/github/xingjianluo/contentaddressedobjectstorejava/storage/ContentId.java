package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/**
 * Content-addressed identifier backed by the hex-encoded SHA-256 digest.
 */
public record ContentId(String sha256Hex) {
    public ContentId {
        if (sha256Hex == null || sha256Hex.isBlank()) {
            throw new IllegalArgumentException("sha256Hex must not be blank");
        }
    }

    public static ContentId of(String sha256Hex) {
        return new ContentId(sha256Hex);
    }

    @Override
    public String toString() {
        return sha256Hex;
    }
}

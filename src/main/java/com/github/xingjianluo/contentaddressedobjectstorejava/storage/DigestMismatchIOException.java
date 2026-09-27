package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import java.io.IOException;

/**
 * IOException specialization that distinguishes a checksum mismatch (logical
 * content corruption) from ordinary physical I/O failures.
 */
public class DigestMismatchIOException extends IOException {

    private final String expectedHash;
    private final String actualHash;

    DigestMismatchIOException(String expectedHash, String actualHash) {
        super("checksum mismatch: expected=" + expectedHash + ", actual=" + actualHash);
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    DigestMismatchIOException(String message) {
        super(message);
        this.expectedHash = null;
        this.actualHash = null;
    }

    public String expectedHash() {
        return expectedHash;
    }

    public String actualHash() {
        return actualHash;
    }
}

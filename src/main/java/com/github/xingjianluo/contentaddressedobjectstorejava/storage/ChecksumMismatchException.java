package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Raised when stored bytes do not hash to their content-addressed id. */
public class ChecksumMismatchException extends StorageException {
    private final String expectedHash;
    private final String actualHash;

    public ChecksumMismatchException(String expectedHash, String actualHash) {
        super("checksum mismatch: expected=" + expectedHash + ", actual=" + actualHash);
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    public String expectedHash() {
        return expectedHash;
    }

    public String actualHash() {
        return actualHash;
    }
}

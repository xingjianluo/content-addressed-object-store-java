package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Raised when a conditional write loses a race or its expected version is wrong. */
public class VersionConflictException extends StorageException {
    public VersionConflictException(String message) {
        super(message);
    }
}

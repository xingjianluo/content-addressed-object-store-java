package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Raised when object/snapshot metadata cannot be persisted atomically. */
public class MetadataPersistenceException extends StorageException {
    public MetadataPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/**
 * Base class for all storage failures so callers can distinguish error causes.
 */
public abstract class StorageException extends Exception {
    protected StorageException(String message) {
        super(message);
    }

    protected StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

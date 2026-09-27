package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Raised when stored bytes are missing/unreadable (physical corruption). */
public class ContentCorruptedException extends StorageException {
    public ContentCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}

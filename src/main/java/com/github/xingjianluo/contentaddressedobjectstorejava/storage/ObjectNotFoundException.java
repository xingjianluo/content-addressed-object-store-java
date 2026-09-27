package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Raised when an object key (or requested version) does not exist. */
public class ObjectNotFoundException extends StorageException {
    public ObjectNotFoundException(String message) {
        super(message);
    }
}

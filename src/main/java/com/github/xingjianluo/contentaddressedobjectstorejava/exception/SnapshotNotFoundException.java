package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

/** 快照不存在。 */
public class SnapshotNotFoundException extends StorageException {
    public SnapshotNotFoundException(String message) {
        super(message);
    }
}

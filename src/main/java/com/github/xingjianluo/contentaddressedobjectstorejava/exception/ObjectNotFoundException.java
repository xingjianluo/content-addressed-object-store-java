package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

/** 对象或指定版本不存在。 */
public class ObjectNotFoundException extends StorageException {
    public ObjectNotFoundException(String message) {
        super(message);
    }
}

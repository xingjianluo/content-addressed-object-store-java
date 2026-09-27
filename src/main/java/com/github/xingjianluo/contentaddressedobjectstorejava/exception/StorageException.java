package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

/** 存储层所有异常的基类，便于调用方按语义区分失败原因。 */
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

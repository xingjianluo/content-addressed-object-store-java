package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

/**
 * 条件写入失败：调用方期望的当前版本与实际当前版本不一致（并发竞争）。
 */
public class VersionConflictException extends StorageException {
    private final String objectKey;
    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(String objectKey, long expectedVersion, long actualVersion) {
        super("version conflict on object '" + objectKey + "': expected "
                + expectedVersion + " but actual " + actualVersion);
        this.objectKey = objectKey;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

/**
 * 元数据中登记的哈希与内容文件实算哈希不一致。
 * 可能是哈希登记错误或内容被静默篡改。
 */
public class ChecksumMismatchException extends StorageException {
    private final ContentHash expectedHash;
    private final String actualHash;

    public ChecksumMismatchException(ContentHash expectedHash, String actualHash) {
        super("checksum mismatch: expected=" + expectedHash.value()
                + ", actual=" + actualHash);
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    public ContentHash getExpectedHash() {
        return expectedHash;
    }

    public String getActualHash() {
        return actualHash;
    }
}

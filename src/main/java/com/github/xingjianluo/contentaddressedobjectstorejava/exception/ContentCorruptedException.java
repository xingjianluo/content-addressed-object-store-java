package com.github.xingjianluo.contentaddressedobjectstorejava.exception;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

/**
 * 内容文件物理损坏：不可读，或实算内容哈希与文件名（内容寻址 ID）不一致。
 */
public class ContentCorruptedException extends StorageException {
    private final ContentHash contentId;

    public ContentCorruptedException(ContentHash contentId, String detail) {
        super("content corrupted for id=" + contentId.value() + ": " + detail);
        this.contentId = contentId;
    }

    public ContentCorruptedException(ContentHash contentId, String detail, Throwable cause) {
        super("content corrupted for id=" + contentId.value() + ": " + detail, cause);
        this.contentId = contentId;
    }

    public ContentHash getContentId() {
        return contentId;
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

import java.io.IOException;
import java.util.Set;

/**
 * 未完成写入登记表：保证“写入中”引用的内容不被 GC 回收。
 * 每条记录是一个 append-only 条目文件，begin/commit/abort 分别增删。
 */
public interface PendingWriteJournal {

    void init() throws IOException;

    /** 登记一个进行中的写入及其引用的内容哈希。 */
    String begin(String objectKey, ContentHash hash) throws IOException;

    /** 标记写入完成，移除登记。 */
    void complete(String entryId) throws IOException;

    /** 中止写入，移除登记。 */
    void abort(String entryId) throws IOException;

    /** 当前所有未完成写入引用的内容哈希集合（GC 根之一）。 */
    Set<ContentHash> referencedHashes() throws IOException;
}

package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * 内容寻址的不可变 blob 存储。
 * 内容以 SHA-256 命名，相同内容物理去重；staging 区存放尚未提交的内容。
 */
public interface BlobStore {

    /** 初始化目录结构。 */
    void init() throws IOException;

    /**
     * 将字节写入 staging 区并校验，返回（内容哈希，staging 临时文件）。
     */
    StagedBlob stage(byte[] content) throws IOException;

    /**
     * 把 staging 文件原子发布为以内容哈希命名的不可变 blob。
     * 相同哈希只保留一份物理副本。
     */
    void commit(StagedBlob staged) throws IOException;

    /** 丢弃 staging 文件。 */
    void abort(StagedBlob staged) throws IOException;

    /**
     * 读取 blob，并校验内容哈希与文件名一致；不一致抛出物理损坏异常。
     */
    byte[] readVerified(ContentHash hash) throws IOException;

    /** blob 是否已存在。 */
    boolean exists(ContentHash hash);

    /** blob 的逻辑字节大小，不存在返回 -1。 */
    long sizeOf(ContentHash hash) throws IOException;

    /** 列出内容目录中所有已发布 blob 的哈希。 */
    Set<ContentHash> listCommitted() throws IOException;

    /** 删除指定 blob（供 GC 调用），返回是否删除成功。 */
    boolean delete(ContentHash hash) throws IOException;

    /** 内容目录下所有已发布 blob 的逻辑字节总和。 */
    long totalCommittedBytes() throws IOException;

    /** staging 临时句柄。 */
    record StagedBlob(ContentHash hash, Path tempPath, long length) {
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.model;

import java.util.List;

/**
 * 一次垃圾回收的结果统计。
 *
 * @param removedHashes   实际被删除的内容哈希
 * @param bytesReclaimed  回收的字节数（按逻辑内容大小累计）
 * @param bytesBeforeGc   回收前内容目录总字节
 * @param bytesAfterGc    回收后内容目录总字节
 */
public record GcResult(
        List<ContentHash> removedHashes,
        long bytesReclaimed,
        long bytesBeforeGc,
        long bytesAfterGc
) {
}

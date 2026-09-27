package com.github.xingjianluo.contentaddressedobjectstorejava.model;

/**
 * 对象某一版本的元数据。
 *
 * @param objectKey 对象名
 * @param version   从 0 起单调递增的版本号
 * @param hash      该版本内容对应的内容哈希
 * @param length    内容字节数
 * @param createdAt 创建时间（毫秒）
 */
public record VersionInfo(
        String objectKey,
        long version,
        ContentHash hash,
        long length,
        long createdAt
) {
}

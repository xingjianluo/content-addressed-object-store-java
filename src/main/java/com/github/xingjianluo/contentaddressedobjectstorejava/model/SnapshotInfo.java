package com.github.xingjianluo.contentaddressedobjectstorejava.model;

/**
 * 快照元数据：快照建立时每个对象当前版本的不可变拷贝。
 *
 * @param snapshotId 快照 ID
 * @param createdAt  创建时间（毫秒）
 */
public record SnapshotInfo(String snapshotId, long createdAt) {
}

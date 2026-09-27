package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快照存储：为创建时刻的“对象 -> 当前版本”映射建立不可变拷贝。
 */
public interface SnapshotStore {

    void init() throws IOException;

    /** 用给定的版本映射创建快照，返回快照 ID。 */
    String create(Map<String, Long> objectVersions, Map<String, ContentHash> objectHashes)
            throws IOException;

    /** 快照中某对象固定的版本号；快照或对象不在快照中返回 empty。 */
    Optional<Long> versionOf(String snapshotId, String objectKey) throws IOException;

    /** 快照中某对象固定的内容哈希。 */
    Optional<ContentHash> hashOf(String snapshotId, String objectKey) throws IOException;

    /** 快照是否存在。 */
    boolean exists(String snapshotId) throws IOException;

    /** 列出全部快照 ID。 */
    List<String> listSnapshots() throws IOException;

    /** 删除快照（解除其对内容的保留）。 */
    void delete(String snapshotId) throws IOException;

    /** 快照引用的全部内容哈希（GC 根之一）。 */
    List<ContentHash> referencedHashes(String snapshotId) throws IOException;
}

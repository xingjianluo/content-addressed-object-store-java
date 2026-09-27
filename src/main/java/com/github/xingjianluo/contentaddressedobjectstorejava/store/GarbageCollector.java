package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 空间回收器（mark-and-sweep）。
 *
 * <p>GC 根集合 = 所有对象全部历史版本引用的哈希
 * + 所有快照引用的哈希 + 所有未完成写入登记的哈希。
 * 任何被引用的内容都不会回收；其余 blob 一律删除。</p>
 *
 * <p>本类假定 {@link #collect()} 在 {@link StoreConcurrency} 写锁下执行，
 * 因此 mark 期间引用关系不会变化，sweep 不会误删正在写入的内容。
 * 重复执行是幂等的：第一次已删除的垃圾在第二次的根集合之外但文件也已不存在，
 * deleteIfExists 静默跳过。</p>
 */
public class GarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollector.class);

    private final BlobStore blobs;
    private final VersionStore versions;
    private final SnapshotStore snapshots;
    private final PendingWriteJournal journal;

    public GarbageCollector(BlobStore blobs, VersionStore versions,
                            SnapshotStore snapshots, PendingWriteJournal journal) {
        this.blobs = blobs;
        this.versions = versions;
        this.snapshots = snapshots;
        this.journal = journal;
    }

    /** 计算全部 GC 根引用的内容哈希（保留判定依据，可直接用于测试）。 */
    public Set<ContentHash> collectReferencedHashes() throws IOException {
        Set<ContentHash> roots = new HashSet<>();

        // 根 1：所有对象的所有历史版本
        int versionCount = 0;
        for (String objectKey : versions.listObjects()) {
            for (VersionInfo v : versions.history(objectKey)) {
                roots.add(v.hash());
                versionCount++;
            }
        }

        // 根 2：所有快照
        int snapshotCount = 0;
        for (String snapshotId : snapshots.listSnapshots()) {
            List<ContentHash> refs = snapshots.referencedHashes(snapshotId);
            roots.addAll(refs);
            snapshotCount++;
        }

        // 根 3：未完成写入
        Set<ContentHash> pending = journal.referencedHashes();
        roots.addAll(pending);

        log.info("[gc] mark roots: versions={}, snapshots={}, pendingWrites={}, uniqueHashes={}",
                versionCount, snapshotCount, pending.size(), roots.size());
        return Set.copyOf(roots);
    }

    /** 执行标记-清除，返回回收统计。 */
    public GcResult collect() throws IOException {
        long bytesBefore = blobs.totalCommittedBytes();
        Set<ContentHash> reachable = collectReferencedHashes();
        Set<ContentHash> committed = blobs.listCommitted();

        List<ContentHash> garbage = new ArrayList<>();
        for (ContentHash hash : committed) {
            if (!reachable.contains(hash)) {
                garbage.add(hash);
            }
        }
        garbage.sort(java.util.Comparator.comparing(ContentHash::value));

        long reclaimed = 0L;
        List<ContentHash> removed = new ArrayList<>();
        for (ContentHash hash : garbage) {
            long size = blobs.sizeOf(hash);
            if (size < 0) {
                size = 0; // 已被并发删除（本设计下不会发生，防御性处理）
            }
            boolean deleted = blobs.delete(hash);
            if (deleted) {
                reclaimed += size;
                removed.add(hash);
            }
        }

        long bytesAfter = blobs.totalCommittedBytes();
        log.info("[gc] sweep committed={}, reachable={}, garbage={}, removed={}, "
                        + "bytesBefore={}, bytesAfter={}, reclaimed={}",
                committed.size(), reachable.size(), garbage.size(), removed.size(),
                bytesBefore, bytesAfter, reclaimed);
        return new GcResult(List.copyOf(removed), reclaimed, bytesBefore, bytesAfter);
    }
}

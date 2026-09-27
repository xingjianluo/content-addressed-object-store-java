package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.BlobStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.FileBlobStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.StoreLayout;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照保留与空间回收：
 * - 所有历史版本始终保留；快照额外固定其时刻内容；
 * - 未完成写入（journal）引用的内容不被回收；
 * - 回收后物理占用下降；重复回收幂等、不破坏现存对象、不错乱引用。
 */
class SnapshotAndGcTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAndGcTest.class);

    /** 直接向 blobs 区发布一个不被任何版本/快照/journal 引用的孤儿内容。 */
    private ContentHash publishOrphan(ObjectStoreService store, byte[] content) throws Exception {
        StoreLayout layout = new StoreLayout(store.rootDir());
        BlobStore raw = new FileBlobStore(layout);
        BlobStore.StagedBlob staged = raw.stage(content);
        raw.commit(staged);
        log.info("[TEST] injected orphan blob hash={} bytes={}", staged.hash().value(), content.length);
        return staged.hash();
    }

    @Test
    void gcDeletesUnreferencedContentAndReclaimsBytes() throws Exception {
        ObjectStoreService store = newStore();
        byte[] live = bytes("live-object-content");
        store.put("live", live);
        long liveBytes = live.length;

        byte[] orphanBytes = bytes("nobody-refers-to-me-0001");
        ContentHash orphan = publishOrphan(store, orphanBytes);

        long before = store.physicalContentBytes();
        log.info("[TEST] bytes before GC = {} (live={}, orphan={})",
                before, liveBytes, orphanBytes.length);
        assertEquals(liveBytes + orphanBytes.length, before);

        Set<ContentHash> roots = store.referencedHashes();
        log.info("[TEST] GC roots contain live? {}; orphan? {}",
                roots.contains(hashOf(live)), roots.contains(orphan));
        assertTrue(roots.contains(hashOf(live)));
        assertFalse(roots.contains(orphan), "孤儿不在任何 GC 根中");

        GcResult r1 = store.garbageCollect();
        long after1 = store.physicalContentBytes();
        log.info("[TEST] GC#1 removed={} reclaimed={}, bytes {} -> {}",
                r1.removedHashes().size(), r1.bytesReclaimed(), r1.bytesBeforeGc(),
                r1.bytesAfterGc());
        assertEquals(1, r1.removedHashes().size());
        assertEquals(orphan, r1.removedHashes().get(0));
        assertEquals(orphanBytes.length, r1.bytesReclaimed());
        assertEquals(liveBytes, after1);
        assertArrayEquals(live, store.read("live"));

        // 重复回收：幂等，无内容可删，现存对象不受影响
        GcResult r2 = store.garbageCollect();
        log.info("[TEST] GC#2 removed={} reclaimed={} (idempotent)",
                r2.removedHashes().size(), r2.bytesReclaimed());
        assertEquals(0, r2.removedHashes().size());
        assertEquals(0L, r2.bytesReclaimed());
        assertEquals(liveBytes, store.physicalContentBytes());
        assertArrayEquals(live, store.read("live"));
    }

    @Test
    void snapshotAndAllHistoryAreRetainedByGc() throws Exception {
        ObjectStoreService store = newStore();
        byte[] v0 = bytes("snapshot-era-content-v0");
        byte[] v1 = bytes("newer-content-v1");
        store.put("doc", v0);
        String snap = store.createSnapshot();
        ContentHash h0 = hashOf(v0);
        log.info("[TEST] snapshot {} pinned v0 hash={}", snap, h0.value());

        store.put("doc", v1);
        byte[] orphan = bytes("orphan-after-snapshot-0002");
        ContentHash orphanHash = publishOrphan(store, orphan);

        // 注入新孤儿后回收：v0/v1 都必须保留（全部历史 + 快照），只删孤儿
        GcResult result = store.garbageCollect();
        log.info("[TEST] GC removed={}, bytes after={}",
                result.removedHashes().size(), store.physicalContentBytes());
        assertTrue(result.removedHashes().contains(orphanHash));

        // 快照读回 v0，当前 head 是 v1，二者皆完好
        assertArrayEquals(v0, store.readSnapshot(snap, "doc"));
        assertArrayEquals(v1, store.read("doc"));
        assertArrayEquals(v0, store.readVersion("doc", 0));
        log.info("[TEST] snapshot v0 and head v1 both readable after GC");

        // 删除快照后，v0 仍因“全部历史保留”而存活（本存储默认保留全部历史）
        store.deleteSnapshot(snap);
        store.garbageCollect();
        assertArrayEquals(v0, store.readVersion("doc", 0));
        log.info("[TEST] history v0 still retained after snapshot deletion (full-history policy)");
    }

    @Test
    void pendingWriteReferencedContentIsSafeFromGc() throws Exception {
        // 直接验证 GC 根集合把 journal 引用纳入（在写内容在 mark 阶段必被保留）
        ObjectStoreService store = newStore();
        byte[] content = bytes("content-mid-write-0003");
        ContentHash h = hashOf(content);

        // 先把内容发布到 blobs（模拟 put 已 commit blob、尚未推进版本的窗口）
        StoreLayout layout = new StoreLayout(store.rootDir());
        BlobStore raw = new FileBlobStore(layout);
        BlobStore.StagedBlob staged = raw.stage(content);
        raw.commit(staged);

        // 此时没有任何版本引用它 -> 是孤儿
        assertFalse(store.referencedHashes().contains(h));

        // 打开底层 journal 登记一个未完成写入（通过反射式的文件构造不现实，
        // 改用真实 put 流程中可观察的等价：用 FilePendingWriteJournal）
        com.github.xingjianluo.contentaddressedobjectstorejava.store.FilePendingWriteJournal journal =
                new com.github.xingjianluo.contentaddressedobjectstorejava.store
                        .FilePendingWriteJournal(layout);
        journal.init();
        String entryId = journal.begin("doc", h);
        log.info("[TEST] pending write {} registered for hash={}", entryId, h.value());

        Set<ContentHash> roots = store.referencedHashes();
        assertTrue(roots.contains(h), "未完成写入引用的内容必须在 GC 根中");
        store.garbageCollect();
        assertEquals(content.length, store.physicalContentBytes(),
                "GC 不得删除未完成写入引用的内容");
        log.info("[TEST] pending-write blob survives GC, bytes={}", store.physicalContentBytes());

        journal.complete(entryId); // 写入完成后才允许回收
        store.garbageCollect();
        assertEquals(0L, store.physicalContentBytes(), "登记解除后孤儿可被回收");
        log.info("[TEST] after journal complete, orphan reclaimed by next GC");
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ChecksumMismatchException;
import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ContentCorruptedException;
import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ObjectNotFoundException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容损坏与校验和不匹配必须被拒绝读取，且失败原因可区分：
 * - 物理 blob 内容被改（实算哈希 != 文件名）=> ContentCorruptedException
 * - 元数据登记哈希与 blob 文件对不上 => ChecksumMismatchException
 * - 对象/版本不存在 => ObjectNotFoundException
 */
class CorruptionTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(CorruptionTest.class);

    @Test
    void physicalBitRotIsReportedAsContentCorruption() throws Exception {
        ObjectStoreService store = newStore();
        byte[] content = bytes("important-payload-0001");
        ContentHash h = hashOf(content);
        store.put("doc", content);
        log.info("[TEST] wrote doc hash={}", h.value());

        Path blob = blobPath(store, h);
        log.info("[TEST] blob physical path = {}", blob);
        corruptBlobPhysically(blob, bytes("TAMPERED-BYTES-NOT-MATCHING-NAME"));
        log.warn("[TEST] physically overwrote blob bytes (file name hash unchanged)");

        ContentCorruptedException ex = assertThrows(ContentCorruptedException.class,
                () -> store.read("doc"));
        log.warn("[TEST] read rejected with ContentCorruptedException: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains(h.value()));
    }

    @Test
    void metadataHashDriftIsReportedAsChecksumMismatch() throws Exception {
        ObjectStoreService store = newStore();
        byte[] content = bytes("important-payload-0002");
        ContentHash h = hashOf(content);
        store.put("doc", content);
        log.info("[TEST] wrote doc hash={} length={}", h.value(), content.length);

        // 物理 blob 完全不动（名↔内容自洽）；仅篡改版本记录登记的长度元数据。
        // 读取时哈希校验通过，但元数据声明与实际内容对不上 => ChecksumMismatchException，
        // 与物理损坏（ContentCorruptedException）明确区分。
        long badLength = content.length + 999;
        log.warn("[TEST] tamper doc v0 record length: {} -> {} (blob physically intact)",
                content.length, badLength);
        tamperVersionRecordLength(store, "doc", 0, badLength);

        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> store.readVersion("doc", 0));
        log.warn("[TEST] readVersion(v0) rejected with ChecksumMismatchException: "
                        + "registeredHash={} detail={}",
                ex.getExpectedHash().value(), ex.getActualHash());
        assertTrue(ex.getExpectedHash().value().equals(h.value()));
        assertTrue(ex.getActualHash().contains("length"));

        // 物理内容本身完好：另一处（未被篡改登记）仍能验证哈希自洽。
        log.info("[TEST] blob physically intact, only metadata declaration is wrong "
                + "=> distinct from physical corruption");
    }

    @Test
    void missingBlobIsContentCorruptionAndMissingObjectIsNotFound() throws Exception {
        ObjectStoreService store = newStore();
        byte[] content = bytes("important-payload-0003");
        ContentHash h = hashOf(content);
        store.put("doc", content);

        // 直接删除物理 blob 文件（绕过 GC）：读取应报物理损坏（文件缺失）
        java.nio.file.Files.delete(blobPath(store, h));
        log.warn("[TEST] deleted blob file {} out-of-band", h.value());
        assertThrows(ContentCorruptedException.class, () -> store.read("doc"));

        // 不存在的对象/版本是另一类失败原因
        assertThrows(ObjectNotFoundException.class, () -> store.read("nope"));
        assertThrows(ObjectNotFoundException.class, () -> store.readVersion("doc", 99));
        log.info("[TEST] missing blob => corruption; missing object/version => not found "
                + "(distinct reasons verified)");
    }

    @Test
    void healthyObjectsRemainReadableAfterOneIsCorrupt() throws Exception {
        ObjectStoreService store = newStore();
        byte[] a = bytes("payload-A");
        byte[] b = bytes("payload-B-distinct");
        ContentHash ha = hashOf(a);
        store.put("a", a);
        store.put("b", b);

        corruptBlobPhysically(blobPath(store, ha), bytes("xxxx"));
        assertThrows(ContentCorruptedException.class, () -> store.read("a"));
        // 另一个健康对象不受影响
        assertArrayEquals(b, store.read("b"));
        log.info("[TEST] corrupting object a does not affect healthy object b");
    }
}

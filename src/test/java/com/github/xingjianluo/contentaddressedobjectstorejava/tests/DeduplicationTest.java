package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去重与独立寻址：
 * 相同内容只占一份物理空间，但不同对象/不同版本仍可独立读取。
 */
class DeduplicationTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationTest.class);

    @Test
    void sameContentAcrossObjectsIsDeduplicatedButIndependentlyReadable() throws Exception {
        ObjectStoreService store = newStore();
        byte[] content = bytes("hello-dedup-world");
        ContentHash h = hashOf(content);
        log.info("[TEST] content hash = {}, length = {}", h.value(), content.length);

        // 三个不同对象写同样内容
        VersionInfo vA = store.put("obj-a", content);
        VersionInfo vB = store.put("obj-b", content);
        VersionInfo vC = store.put("obj-c", content);

        log.info("[TEST] wrote obj-a v{} hash={}", vA.version(), vA.hash().value());
        log.info("[TEST] wrote obj-b v{} hash={}", vB.version(), vC.version());
        log.info("[TEST] wrote obj-c v{} hash={}", vC.version(), vC.hash().value());

        // 判定依据 1：三者哈希一致
        assertEquals(h, vA.hash());
        assertEquals(vA.hash(), vB.hash());
        assertEquals(vA.hash(), vC.hash());

        // 判定依据 2：物理上只有一份 blob，占用等于一份内容大小
        long physical = store.physicalContentBytes();
        log.info("[TEST] physical blobs bytes after 3 identical writes = {} (expect {})",
                physical, (long) content.length);
        assertEquals(content.length, physical,
                "相同内容必须物理去重，只占一份内容大小");

        // 判定依据 3：三个对象可独立寻址、内容一致
        assertArrayEquals(content, store.read("obj-a"));
        assertArrayEquals(content, store.read("obj-b"));
        assertArrayEquals(content, store.read("obj-c"));
        log.info("[TEST] all three objects independently resolve to identical content");

        // 同一对象再写一次相同内容：新版本仍独立寻址，但不新增物理占用
        VersionInfo v2 = store.put("obj-a", content);
        assertEquals(1, v2.version());
        assertEquals(h, v2.hash());
        assertEquals(content.length, store.physicalContentBytes());
        assertArrayEquals(content, store.readVersion("obj-a", 0));
        assertArrayEquals(content, store.readVersion("obj-a", 1));
        log.info("[TEST] same object same content -> new version independently readable, "
                + "no extra physical bytes");

        // 不同内容应新增物理占用
        byte[] other = bytes("different-content-123");
        store.put("obj-d", other);
        long after = store.physicalContentBytes();
        log.info("[TEST] physical bytes after distinct content = {} (expect {})",
                after, (long) content.length + other.length);
        assertEquals(content.length + other.length, after);
        assertTrue(after > content.length);
    }
}

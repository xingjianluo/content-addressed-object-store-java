package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.VersionConflictException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 版本管理与条件写入：
 * - 版本号单调递增，历史版本永久保留可读；
 * - putIfVersion 仅在期望版本匹配时原子生效，否则抛 VersionConflictException；
 * - 冲突写入既不产生新版本，也不覆盖历史。
 */
class ConditionalWriteTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ConditionalWriteTest.class);

    @Test
    void versionsAccumulateAndHistoryIsImmutable() throws Exception {
        ObjectStoreService store = newStore();
        byte[] c0 = bytes("v0-content");
        byte[] c1 = bytes("v1-content");
        byte[] c2 = bytes("v2-content");

        VersionInfo v0 = store.putIfVersion("cfg", -1, c0);
        VersionInfo v1 = store.putIfVersion("cfg", 0, c1);
        VersionInfo v2 = store.putIfVersion("cfg", 1, c2);
        assertEquals(0, v0.version());
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        log.info("[TEST] committed versions 0,1,2 with distinct content");

        // 历史版本一个不少、内容不可变
        List<VersionInfo> history = store.history("cfg");
        assertEquals(3, history.size());
        assertArrayEquals(c0, store.readVersion("cfg", 0));
        assertArrayEquals(c1, store.readVersion("cfg", 1));
        assertArrayEquals(c2, store.readVersion("cfg", 2));
        assertArrayEquals(c2, store.read("cfg")); // head 是最新
        log.info("[TEST] all 3 history versions preserved; head=v2");
    }

    @Test
    void staleExpectedVersionIsRejectedAndLeavesStateUntouched() throws Exception {
        ObjectStoreService store = newStore();
        store.putIfVersion("cfg", -1, bytes("init"));
        store.putIfVersion("cfg", 0, bytes("second")); // head 现在是 v1

        // 一个迟到的、仍以为 head=v0 的条件写必须失败
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> store.putIfVersion("cfg", 0, bytes("late-write")));
        log.warn("[TEST] stale write rejected: {}", ex.getMessage());
        assertEquals(0, ex.getExpectedVersion());
        assertEquals(1, ex.getActualVersion());

        // 失败不得产生中间状态：仍是 2 个版本，head 仍是 v1
        assertEquals(2, store.history("cfg").size());
        assertEquals(1, store.head("cfg").version());
        assertArrayEquals(bytes("second"), store.read("cfg"));
        log.info("[TEST] after conflict: versions=2, head=v1, content unchanged");
    }

    @Test
    void createWithWrongExpectationFailsButCorrectOneSucceeds() throws Exception {
        ObjectStoreService store = newStore();
        // 对象不存在时只有 expectedVersion=-1 能创建
        assertThrows(VersionConflictException.class,
                () -> store.putIfVersion("newobj", 0, bytes("x")));
        VersionInfo created = store.putIfVersion("newobj", -1, bytes("x"));
        assertEquals(0, created.version());
        log.info("[TEST] create requires expectedVersion=-1; conflict otherwise");
    }
}

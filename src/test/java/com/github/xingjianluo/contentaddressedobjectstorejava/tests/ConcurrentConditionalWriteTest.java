package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.VersionConflictException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发条件写入：N 个线程基于同一基准版本竞争提交同一对象，
 * 恰好一个成功，其余全部得到可区分的 VersionConflictException；
 * 最终版本连续无空洞、历史内容一一可读、无中间状态。
 */
class ConcurrentConditionalWriteTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentConditionalWriteTest.class);

    @Test
    void onlyOneWriterWinsPerVersionAndNoHistoryIsLost() throws Exception {
        ObjectStoreService store = newStore();
        store.putIfVersion("counter", -1, bytes("base-v0"));

        int threads = 16;
        int rounds = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<String> winnerContent = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int r = 0; r < rounds; r++) {
                final int round = r;
                long baseVersionRaw = store.head("counter").version();
                final long baseVersion = baseVersionRaw;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < threads; t++) {
                    final int ti = t;
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            byte[] payload = bytes("round" + round + "-writer" + ti);
                            store.putIfVersion("counter", baseVersion, payload);
                            successes.incrementAndGet();
                            winnerContent.add(new String(payload));
                            log.info("[CONCUR] round={} writer={} WON (expected {})",
                                    round, ti, baseVersion);
                        } catch (VersionConflictException e) {
                            conflicts.incrementAndGet();
                            log.info("[CONCUR] round={} writer={} CONFLICT expected={} actual={}",
                                    round, ti, e.getExpectedVersion(), e.getActualVersion());
                        } catch (Exception e) {
                            log.error("[CONCUR] unexpected error", e);
                            throw new RuntimeException(e);
                        } finally {
                            done.countDown();
                        }
                    }));
                }
                start.countDown();
                done.await();
                for (Future<?> f : futures) {
                    f.get(); // 传播意外异常
                }
                // 每轮：恰好 1 个成功，其余冲突；head 推进恰好 1 个版本
                assertEquals(baseVersion + 1, store.head("counter").version(),
                        "head must advance by exactly one per round");
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("[TEST] total successes={}, conflicts={}", successes.get(), conflicts.get());
        assertEquals(rounds, successes.get(), "每轮恰好一个赢家");
        assertEquals(rounds * (threads - 1), conflicts.get(), "其余全部版本冲突");

        // 最终一致性：版本从 0..rounds 连续，每版内容都能读出且互不相同
        List<VersionInfo> history = store.history("counter");
        assertEquals(rounds + 1, history.size());
        Set<String> unique = new HashSet<>();
        for (VersionInfo v : history) {
            assertEquals(v.version(), history.get((int) v.version()).version());
            byte[] data = store.readVersion("counter", v.version());
            unique.add(new String(data));
            assertTrue(data.length > 0);
        }
        assertEquals(history.size(), unique.size(), "每个版本内容唯一可寻址");
        assertArrayEquals(bytes(winnerContent.get(winnerContent.size() - 1)),
                store.read("counter"));
        log.info("[TEST] final versions={} contiguous, all history readable and distinct, "
                + "head content = last winner", history.size());
    }
}

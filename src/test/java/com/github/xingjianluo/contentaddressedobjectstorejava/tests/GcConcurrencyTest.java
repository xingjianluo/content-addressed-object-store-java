package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收期间的并发访问：GC 与持续读/条件写并行。
 * 不变量：
 * - 任何被读到的内容都完整校验通过（无 ContentCorruption/ChecksumMismatch）；
 * - 活对象的每个已发布版本在任何时刻都能读出；
 * - 条件写要么成功推进一个版本，要么明确冲突，无丢失/无中间状态；
 * - 并发结束后再 GC，物理占用稳定（重复 GC 不下降 => 没有误删活内容）。
 */
class GcConcurrencyTest extends StoreTestSupport {

    private static final Logger log = LoggerFactory.getLogger(GcConcurrencyTest.class);

    @Test
    void concurrentReadsWritesAndGcKeepStateConsistent() throws Exception {
        final ObjectStoreService store = newStore();
        store.putIfVersion("hot", -1, bytes("init-0"));

        int readerThreads = 6;
        int writerThreads = 4;
        int gcRounds = 20;
        long durationMillis = 2500;

        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + writerThreads + 1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writesOk = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger gcs = new AtomicInteger();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();

            // 读线程：循环读取所有已发布版本；任何损坏/异常都记录为失败
            for (int r = 0; r < readerThreads; r++) {
                futures.add(pool.submit(() -> {
                    try {
                        while (stop.getCount() > 0) {
                            VersionInfo head = store.head("hot");
                            // 读当前版本
                            byte[] cur = store.read("hot");
                            reads.incrementAndGet();
                            // 随机读一个历史版本：必须始终可读且内容稳定
                            long pick = head.version() == 0 ? 0
                                    : (System.nanoTime() % (head.version() + 1));
                            byte[] hist = store.readVersion("hot", pick);
                            if (hist.length == 0) {
                                throw new AssertionError("empty historical read");
                            }
                        }
                    } catch (java.util.concurrent.CompletionException e) {
                        // 关闭时的包装异常忽略
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }

            // 写线程：基于刚读到的 head 做条件写（乐观并发）
            for (int w = 0; w < writerThreads; w++) {
                final int wi = w;
                futures.add(pool.submit(() -> {
                    try {
                        long expected = store.head("hot").version();
                        int local = 0;
                        while (stop.getCount() > 0) {
                            try {
                                byte[] payload = bytes("w" + wi + "-" + local + "-"
                                        + System.nanoTime());
                                store.putIfVersion("hot", expected, payload);
                                writesOk.incrementAndGet();
                                expected = expected + 1;
                                local++;
                            } catch (com.github.xingjianluo.contentaddressedobjectstorejava
                                    .exception.VersionConflictException e) {
                                conflicts.incrementAndGet();
                                // 竞争失败：刷新基准版本后重试
                                expected = store.head("hot").version();
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }

            // GC 线程：周期性制造孤儿并回收
            Future<?> gcFuture = pool.submit(() -> {
                try {
                    for (int g = 0; g < gcRounds && stop.getCount() > 0; g++) {
                        // 写一个短命的不同对象再让其 blob 变孤儿不现实（版本会一直引用）；
                        // 这里直接反复 GC：验证它与读写并发时不会误删活内容
                        store.garbageCollect();
                        gcs.incrementAndGet();
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                }
            });

            Thread.sleep(durationMillis);
            stop.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            gcFuture.get();
        } finally {
            pool.shutdownNow();
        }

        log.info("[TEST] reads={}, writesOk={}, conflicts={}, gcs={}, errors={}",
                reads.get(), writesOk.get(), conflicts.get(), gcs.get(), errors.size());
        assertTrue(errors.isEmpty(),
                "并发期间不允许出现损坏/校验/不一致错误: " + errors);
        assertTrue(writesOk.get() > 0, "应有成功写入");

        // 结束后：版本连续、每个版本可读且唯一。
        // 竞争失败的写入会留下“已发布但从未被版本引用”的合法垃圾，
        // 先做一次 GC 收敛，随后再 GC 必须稳定（证明未误删任何被引用内容）。
        List<VersionInfo> history = store.history("hot");
        log.info("[TEST] final history size = {}", history.size());
        assertEquals(history.size() - 1, history.get(history.size() - 1).version());

        com.github.xingjianluo.contentaddressedobjectstorejava.model.GcResult convergence =
                store.garbageCollect();
        log.info("[TEST] convergence GC removed={} (failed-writer blobs)",
                convergence.removedHashes().size());
        long stableBytes = store.physicalContentBytes();
        com.github.xingjianluo.contentaddressedobjectstorejava.model.GcResult second =
                store.garbageCollect();
        assertEquals(0, second.removedHashes().size(),
                "稳态下第二次 GC 不得再删除任何内容");
        assertEquals(stableBytes, store.physicalContentBytes(),
                "稳态下重复 GC 物理占用必须不变");

        for (VersionInfo v : history) {
            byte[] data = store.readVersion("hot", v.version());
            assertTrue(data.length > 0);
        }
        assertArrayEquals(store.readVersion("hot", history.size() - 1), store.read("hot"));
        log.info("[TEST] all {} versions verified readable; GC stable bytes={}",
                history.size(), stableBytes);
    }
}

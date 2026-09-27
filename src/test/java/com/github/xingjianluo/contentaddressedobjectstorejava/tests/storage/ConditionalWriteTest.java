package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.VersionConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conditional writes: exactly one writer wins an update race, losers get a
 * distinct VersionConflictException, no version numbers are skipped/duplicated
 * and no history is overwritten.
 */
class ConditionalWriteTest {

    private static final Logger log = LoggerFactory.getLogger(ConditionalWriteTest.class);

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void staleExpectedVersionIsRejected(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            ObjectVersion v1 = store.putIfAbsent("doc", bytes("v1"));
            ObjectVersion v2 = store.putIfVersion("doc", bytes("v2"), v1.version());
            assertEquals(2, v2.version());

            // Writer holds the stale expectation version=1 while current=2.
            VersionConflictException ex = assertThrows(VersionConflictException.class,
                    () -> store.putIfVersion("doc", bytes("stale"), v1.version()));
            log.info("[cas] stale write rejected: {}", ex.getMessage());

            // Correct expectation still succeeds; history intact.
            ObjectVersion v3 = store.putIfVersion("doc", bytes("v3"), v2.version());
            assertEquals(3, v3.version());
            List<ObjectVersion> history = store.objects().history("doc");
            assertEquals(List.of(1L, 2L, 3L), history.stream().map(ObjectVersion::version).toList());
            log.info("[cas] history after rejected+accepted writes = versions 1,2,3 (no loss)");
        }
    }

    @Test
    void duplicateCreateIsRejected(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            store.putIfAbsent("doc", bytes("first"));
            VersionConflictException ex = assertThrows(VersionConflictException.class,
                    () -> store.putIfAbsent("doc", bytes("again")));
            log.info("[cas] duplicate create rejected: {}", ex.getMessage());
            assertEquals(1, store.objects().history("doc").size());
        }
    }

    @Test
    void concurrentCompetingWritersExactlyOneWins(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            // Seed version 1.
            ObjectVersion seed = store.putIfAbsent("counter", bytes("seed"));
            long startVersion = seed.version();

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();

            try {
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        try {
                            ready.countDown();
                            assertTrue(go.await(10, TimeUnit.SECONDS));
                            // Every competitor assumes it is the first to move from version 1.
                            ObjectVersion won = store.putIfVersion(
                                    "counter", bytes("write-" + idx), startVersion);
                            wins.incrementAndGet();
                            log.info("[race] writer {} WON -> committed version {}",
                                    idx, won.version());
                        } catch (VersionConflictException e) {
                            conflicts.incrementAndGet();
                            log.info("[race] writer {} LOST: {}", idx, e.getMessage());
                        } catch (Exception e) {
                            log.error("[race] writer {} unexpected failure", idx, e);
                            throw new RuntimeException(e);
                        }
                    }));
                }
                ready.await();
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            log.info("[race] result: wins={} conflicts={} total={}",
                    wins.get(), conflicts.get(), threads);
            assertEquals(1, wins.get(), "exactly one competing writer must win");
            assertEquals(threads - 1, conflicts.get(), "all losers must see VersionConflict");

            List<ObjectVersion> history = store.objects().history("counter");
            assertEquals(2, history.size(), "one new version appended, nothing overwritten");
            assertEquals(List.of(1L, 2L),
                    history.stream().map(ObjectVersion::version).toList(),
                    "version numbers must be contiguous");
            log.info("[race] final history versions={}, no gap/duplicate/intermediate state",
                    history.stream().map(ObjectVersion::version).toList());
        }
    }

    @Test
    void chainedOptimisticUpdatesSerialize(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            store.putIfAbsent("counter", bytes("0"));
            int writers = 8;
            int perWriter = 20;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            AtomicInteger conflicts = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(writers);
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perWriter; i++) {
                            while (true) {
                                long expected = store.objects().getCurrent("counter").version();
                                try {
                                    store.putIfVersion("counter",
                                            bytes("v-" + expected + "-" + i), expected);
                                    break;
                                } catch (VersionConflictException lost) {
                                    conflicts.incrementAndGet();
                                    // retry with refreshed expected version
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            pool.shutdownNow();

            long finalVersion = store.objects().getCurrent("counter").version();
            log.info("[chain] committed {} versions after {} retries-on-conflict",
                    finalVersion, conflicts.get());
            assertEquals(1 + (long) writers * perWriter, finalVersion,
                    "every successful CAS append must be visible with no lost update");
        }
    }
}

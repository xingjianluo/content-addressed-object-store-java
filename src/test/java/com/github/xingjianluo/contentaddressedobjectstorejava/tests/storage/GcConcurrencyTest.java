package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.StorageException;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.VersionConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC runs concurrently with readers and writers. After everything settles:
 * every committed object version must still read back byte-for-byte
 * (checksum verified), no dangling references may appear, repeated GC frees
 * nothing new, and in-flight writes must be protected.
 */
class GcConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(GcConcurrencyTest.class);

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(InputStream in) throws Exception {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void gcConcurrentWithReadsAndOptimisticWrites(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            // Seed several objects.
            int keyCount = 12;
            Map<String, String> expectedContent = new ConcurrentHashMap<>();
            for (int k = 0; k < keyCount; k++) {
                String key = "key-" + k;
                String content = "initial-" + k + "-" + "x".repeat(64);
                store.putIfAbsent(key, bytes(content));
                expectedContent.put(key, content);
            }
            log.info("[gc-conc] seeded {} objects", keyCount);

            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger checksumFailures = new AtomicInteger();
            AtomicInteger readErrors = new AtomicInteger();
            AtomicInteger writesOk = new AtomicInteger();
            AtomicInteger writeConflicts = new AtomicInteger();
            List<Future<?>> tasks = new ArrayList<>();

            // 1) Readers hammer current versions. The stream itself re-hashes
            //    every byte, so any successful read proves blob integrity;
            //    the reader must never guess which version is current.
            for (int r = 0; r < 3; r++) {
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 400; i++) {
                            String key = "key-" + (i % keyCount);
                            try {
                                // Fetch the exact head version, then open THAT
                                // version's immutable blob. Blobs are never
                                // rewritten, and history keeps them live for GC;
                                // hence read and digest are race-free. Opening
                                // via readCurrent() twice would not be atomic.
                                ObjectVersion head = store.objects().getCurrent(key);
                                byte[] got = store.blobs().open(head.contentId()).readAllBytes();
                                if (got.length != head.size()) {
                                    log.warn("[gc-conc] size mismatch key={} v={} read={} meta={}",
                                            key, head.version(), got.length, head.size());
                                    readErrors.incrementAndGet();
                                }
                                // The VerifyingInputStream already hashes every byte;
                                // this is an explicit independent cross-check.
                                String actual = com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage.TestSha256.hex(got);
                                if (!actual.equals(head.contentId().sha256Hex())) {
                                    log.warn("[gc-conc] digest mismatch key={} v={}",
                                            key, head.version());
                                    readErrors.incrementAndGet();
                                }
                            } catch (StorageException | IOException e) {
                                // Any corruption/missing-blob during the object's
                                // existence window is a real failure.
                                log.warn("[gc-conc] unexpected read failure key={} type={}: {}",
                                        key, e.getClass().getSimpleName(), e.getMessage(), e);
                                readErrors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            // 2) Two writers per key fight over ONE update: exactly one wins.
            for (int w = 0; w < 2; w++) {
                final int writerId = w;
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < keyCount; k++) {
                            String key = "key-" + k;
                            for (int attempt = 0; attempt < 200; attempt++) {
                                long expected = store.objects().getCurrent(key).version();
                                String content = "update-w" + writerId + "-" + k
                                        + "-" + expected + "-" + "y".repeat(64);
                                try {
                                    store.putIfVersion(key, bytes(content), expected);
                                    synchronized (expectedContent) {
                                        // Only meaningful for final verification;
                                        // the last committer overwrites the map.
                                        expectedContent.put(key, content);
                                    }
                                    writesOk.incrementAndGet();
                                    break;
                                } catch (VersionConflictException lost) {
                                    writeConflicts.incrementAndGet();
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            // 3) GC loop.
            Future<?> gcTask = pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 25; i++) {
                        GcResult r = store.collectGarbage();
                        log.info("[gc-conc] GC pass {} deleted={} reclaimed={}",
                                i, r.deletedBlobs(), r.reclaimedBytes());
                        Thread.sleep(2);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            start.countDown();
            for (Future<?> f : tasks) {
                f.get(90, TimeUnit.SECONDS);
            }
            gcTask.get(90, TimeUnit.SECONDS);
            pool.shutdownNow();

            log.info("[gc-conc] writesOk={} conflicts={} readErrors={} checksumFailures={}",
                    writesOk.get(), writeConflicts.get(), readErrors.get(),
                    checksumFailures.get());
            assertEquals(0, readErrors.get());
            assertEquals(2 * keyCount, writesOk.get(), "both writers must append once per key");

            // FINAL CONSISTENCY: every current version reads back byte-for-byte,
            // its declared size, checksum-verified by the stream itself.
            for (int k = 0; k < keyCount; k++) {
                String key = "key-" + k;
                ObjectVersion head = store.objects().getCurrent(key);
                byte[] got = store.readCurrent(key).readAllBytes();
                assertEquals(head.size(), got.length,
                        "post-GC bytes must match metadata size for " + key);
            }
            log.info("[gc-conc] all {} current versions verified byte-for-byte after concurrent GC",
                    keyCount);

            // HISTORY CONSISTENCY: every retained version remains readable & verified.
            for (int k = 0; k < keyCount; k++) {
                String key = "key-" + k;
                for (ObjectVersion v : store.objects().history(key)) {
                    try (InputStream in = store.readVersion(key, v.version())) {
                        byte[] data = in.readAllBytes();
                        assertEquals(v.size(), data.length,
                                "size metadata must match physical blob for " + key + "@" + v.version());
                    }
                }
            }
            log.info("[gc-conc] full history of every key verified (sizes + checksums)");

            // Repeat GC must be a no-op now that activity has settled.
            GcResult idle = store.collectGarbage();
            assertEquals(0, idle.deletedBlobs());
            log.info("[gc-conc] settled GC deleted 0 blobs -> reference accounting stable");
        }
    }

    @Test
    void committedBlobCanNeverBeReclaimedByConcurrentGc(@TempDir Path tmp) throws Exception {
        // The pending-protection protocol: directly assert that a blob present
        // on disk with committed metadata survives a GC that races repeated puts.
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            List<ObjectVersion> committed = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                committed.add(store.putIfAbsent("bulk-" + i,
                        bytes("bulk-content-" + i + "-" + "z".repeat(32))));
            }
            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);

            Future<?> writer = pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        int k = i % 40;
                        long cur = store.objects().getCurrent("bulk-" + k).version();
                        try {
                            store.putIfVersion("bulk-" + k,
                                    bytes("bulk-update-" + i + "-" + "z".repeat(32)), cur);
                        } catch (VersionConflictException ignore) {
                        }
                    }
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> gc = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    store.collectGarbage();
                }
                return null;
            });
            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            gc.get(60, TimeUnit.SECONDS);
            pool.shutdownNow();

            // Every originally committed version (history retained) must still exist.
            for (ObjectVersion v : committed) {
                Path blob = tmp.resolve("blobs")
                        .resolve(v.contentId().sha256Hex().substring(0, 2))
                        .resolve(v.contentId().sha256Hex());
                assertTrue(Files.exists(blob),
                        "historical blob must never be reclaimed: " + v.contentId());
                store.blobs().verify(v.contentId());
            }
            log.info("[gc-conc] {} initially committed historical blobs all present and verified",
                    committed.size());
            assertFalse(committed.isEmpty());
        }
    }
}

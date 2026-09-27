package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.BlobStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentId;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.SnapshotStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.GarbageCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blob that is published to blobs/ but whose object metadata has not yet
 * committed must be protected from GC by the pending registry.
 */
class InFlightProtectionTest {

    private static final Logger log = LoggerFactory.getLogger(InFlightProtectionTest.class);

    /** InputStream that pauses once, simulating a slow upload between bytes. */
    private static final class SlowStream extends InputStream {
        private final byte[] data;
        private int pos;
        private boolean paused;
        private final Runnable onMidpoint;

        SlowStream(byte[] data, Runnable onMidpoint) {
            this.data = data;
            this.onMidpoint = onMidpoint;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            if (!paused && pos >= data.length / 2) {
                paused = true;
                onMidpoint.run();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    void pendingBlobIsNotReclaimedAndCommitsAfterGc(@TempDir Path tmp) throws Exception {
        BlobStore blobs = new BlobStore(tmp);
        ObjectStore objects = new ObjectStore(blobs, tmp);
        SnapshotStore snapshots = new SnapshotStore(objects, tmp);
        GarbageCollector gc = new GarbageCollector(blobs, objects, snapshots);

        byte[] payload = ("inflight-" + "q".repeat(128)).getBytes(StandardCharsets.UTF_8);
        AtomicReference<ContentId> pendingId = new AtomicReference<>();
        AtomicReference<Exception> writerError = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                // Use the components directly so we can inspect pending state.
                ContentId id = blobs.put(new SlowStream(payload, () -> {
                    // midpoint during staging: nothing published yet
                }));
                // Simulate the metadata-commit window: GC runs right here while
                // this id is registered pending.
                pendingId.set(id);
                synchronized (pendingId) {
                    pendingId.notifyAll();
                }
                try {
                    Thread.sleep(200); // widen the window for GC
                    objects.putIfAbsent("late-object", id, payload.length);
                } finally {
                    blobs.releasePending(id);
                }
            } catch (Exception e) {
                writerError.set(e);
            }
        }, "slow-writer");
        writer.start();

        // Wait until pending registration is observable, then run GC repeatedly.
        synchronized (pendingId) {
            while (pendingId.get() == null) {
                pendingId.wait(5000);
            }
        }
        log.info("[inflight] pending content observed: {}", pendingId.get());
        GcResult r1 = gc.collect();
        GcResult r2 = gc.collect();
        log.info("[inflight] GC while pending: deleted={} and {}", r1.deletedBlobs(), r2.deletedBlobs());
        assertEquals(0, r1.deletedBlobs() + r2.deletedBlobs(),
                "GC must not delete an in-flight blob");
        assertNotNull(blobs.open(pendingId.get()), "pending blob must remain readable");

        writer.join(10000);
        assertEquals(null, writerError.get());

        // After commit, the object resolves and GC still keeps it (referenced).
        assertEquals(pendingId.get(), objects.getCurrent("late-object").contentId());
        GcResult afterCommit = gc.collect();
        assertEquals(0, afterCommit.deletedBlobs());
        log.info("[inflight] committed object survives GC; content verifiable: {}",
                new String(blobs.open(pendingId.get()).readAllBytes(), StandardCharsets.UTF_8)
                        .startsWith("inflight-"));
    }

    @Test
    void orphanBlobAfterFailedCommitIsReclaimedByLaterGc(@TempDir Path tmp) throws Exception {
        // A CAS loser's blob never gets metadata: once pending is released it
        // becomes a normal orphan and the NEXT GC must reclaim it.
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            store.putIfAbsent("k", new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));
            ContentId orphan = store.blobs().put(
                    new ByteArrayInputStream("loser-content".getBytes(StandardCharsets.UTF_8)));
            // simulate failed conditional commit: just release pending
            store.blobs().releasePending(orphan);
            log.info("[inflight] simulated orphan content {} after failed CAS", orphan);

            GcResult r = store.collectGarbage();
            assertEquals(1, r.deletedBlobs());
            assertTrue(r.reclaimedBytes() > 0);
            GcResult again = store.collectGarbage();
            assertEquals(0, again.deletedBlobs());
            // Existing object untouched.
            assertEquals("first", new String(store.readCurrent("k").readAllBytes(),
                    StandardCharsets.UTF_8));
            log.info("[inflight] orphan reclaimed, committed object intact, repeat GC no-op");
        }
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.GcResult;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot retention and garbage collection:
 * - blobs referenced by history/snapshots/in-flight writes survive,
 * - unreferenced blobs are deleted and actual disk usage drops,
 * - repeated GC is a safe no-op and never corrupts live references.
 */
class SnapshotGcTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotGcTest.class);

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static long totalBlobBytes(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root.resolve("blobs"))) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sum();
        }
    }

    private static long blobCount(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root.resolve("blobs"))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void gcReclaimsOnlyUnreferencedBlobsAndRespectsHistory(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            ObjectVersion v1 = store.putIfAbsent("doc", bytes("version-one-payload"));
            ObjectVersion v2 = store.putIfVersion("doc", bytes("version-two-payload"),
                    v1.version());
            store.putIfAbsent("temp", bytes("throwaway-object-content"));
            store.objects().delete("temp"); // metadata gone; blob becomes unreferenced

            long withOrphan = totalBlobBytes(tmp);
            log.info("[gc] before collect: blobs={} bytes={}", blobCount(tmp), withOrphan);

            GcResult r1 = store.collectGarbage();
            long afterFirst = totalBlobBytes(tmp);
            log.info("[gc] first pass deleted={} reclaimed={} -> blobs={} bytes={}",
                    r1.deletedBlobs(), r1.reclaimedBytes(), blobCount(tmp), afterFirst);

            assertEquals(1, r1.deletedBlobs());
            assertTrue(r1.reclaimedBytes() > 0);
            assertTrue(afterFirst < withOrphan, "actual disk usage must drop");

            // Both historical versions remain readable (full history is live).
            assertEquals("version-one-payload",
                    new String(store.readVersion("doc", 1).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("version-two-payload",
                    new String(store.readVersion("doc", 2).readAllBytes(), StandardCharsets.UTF_8));
            log.info("[gc] historical versions 1 and 2 still readable after collection");

            // Repeated GC is an idempotent no-op and preserves state.
            GcResult r2 = store.collectGarbage();
            GcResult r3 = store.collectGarbage();
            log.info("[gc] repeated passes deleted={} and {}", r2.deletedBlobs(), r3.deletedBlobs());
            assertEquals(0, r2.deletedBlobs());
            assertEquals(0, r3.deletedBlobs());
            assertEquals(afterFirst, totalBlobBytes(tmp));
            assertEquals("version-two-payload",
                    new String(store.readCurrent("doc").readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void snapshotPinsContentEvenAfterObjectDeleteAndRewrite(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            ObjectVersion v1 = store.putIfAbsent("doc", bytes("snapshotted-v1"));
            Snapshot snap = store.snapshot("before-change");
            log.info("[snap] created snapshot {} pinning doc@{}", snap.snapshotId(), v1.version());

            // Rewrite history: new version, then delete the object entirely.
            ObjectVersion v2 = store.putIfVersion("doc", bytes("snapshotted-v2"), v1.version());
            long bytesV1 = Files.size(tmp.resolve("blobs")
                    .resolve(v1.contentId().sha256Hex().substring(0, 2))
                    .resolve(v1.contentId().sha256Hex()));
            store.objects().delete("doc");

            GcResult r = store.collectGarbage();
            log.info("[snap] GC after delete: deleted={} (snapshot predates v2; v1 pinned, v2 unreferenced)",
                    r.deletedBlobs());
            assertEquals(1, r.deletedBlobs(), "v2 is unreferenced; only snapshot-pinned v1 survives");
            // v1 blob physically still present
            assertTrue(Files.exists(tmp.resolve("blobs")
                    .resolve(v1.contentId().sha256Hex().substring(0, 2))
                    .resolve(v1.contentId().sha256Hex())),
                    "snapshot-pinned blob must not be deleted");
            assertFalse(Files.exists(tmp.resolve("blobs")
                    .resolve(v2.contentId().sha256Hex().substring(0, 2))
                    .resolve(v2.contentId().sha256Hex())),
                    "unreferenced v2 blob should have been reclaimed");

            // The snapshot entry directly pins v1's content id regardless of
            // the object's current metadata; prove the blob is intact by
            // opening it through BlobStore (checksum verified on read).
            Snapshot.Entry entry = snap.versions().get("doc");
            assertEquals(1L, entry.version());
            assertEquals(v1.contentId(), entry.contentId());
            store.blobs().verify(entry.contentId());
            assertEquals("snapshotted-v1",
                    new String(store.blobs().open(entry.contentId()).readAllBytes(),
                            StandardCharsets.UTF_8));
            log.info("[snap] snapshot still resolves to original content {} ({} bytes)",
                    v1.contentId(), bytesV1);

            // Restore object too: identical bytes dedup onto the same pinned blob.
            store.putIfAbsent("doc", bytes("snapshotted-v1"));
            assertEquals(v1.contentId(),
                    store.objects().getCurrent("doc").contentId());

            // Dropping the snapshot makes its uniquely-referenced content reclaimable.
            assertTrue(store.snapshots().delete(snap.snapshotId()));
            store.objects().delete("doc");
            // v2's blob is also unreferenced now; both unique blobs should go.
            GcResult r2 = store.collectGarbage();
            log.info("[snap] GC after snapshot drop: deleted={} reclaimed={}",
                    r2.deletedBlobs(), r2.reclaimedBytes());
            assertTrue(r2.deletedBlobs() >= 1);
            assertEquals(0L, blobCount(tmp), "no live references remain");
            assertFalse(Files.exists(tmp.resolve("blobs")
                    .resolve(v1.contentId().sha256Hex().substring(0, 2))
                    .resolve(v1.contentId().sha256Hex())));
            log.info("[snap] pinned content reclaimed only after snapshot was dropped");
        }
    }
}

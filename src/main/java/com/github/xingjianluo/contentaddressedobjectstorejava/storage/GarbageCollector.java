package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Mark-and-sweep garbage collector for content-addressed blobs.
 *
 * <p>A blob is <b>live</b> if it is referenced by at least one of:</p>
 * <ul>
 *   <li>any version of any object (full version history is retained),</li>
 *   <li>any retained snapshot,</li>
 *   <li>a write in flight between blob publish and metadata commit
 *       ({@link BlobStore}'s pending set, plus every blob physically present).</li>
 * </ul>
 *
 * <p>The whole pass executes under the object store's write lock, so no
 * concurrent put can interleave "blob published / metadata committed" with
 * marking. Sweep only deletes ids that were observed on disk and were not in
 * the live set; a repeated pass with nothing unreferenced frees zero bytes and
 * never touches reachable state.</p>
 */
public class GarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollector.class);

    private final BlobStore blobs;
    private final ObjectStore objects;
    private final SnapshotStore snapshots;

    public GarbageCollector(BlobStore blobs, ObjectStore objects, SnapshotStore snapshots) {
        this.blobs = blobs;
        this.objects = objects;
        this.snapshots = snapshots;
    }

    public GcResult collect() {
        objects.lock.writeLock().lock();
        try {
            // MARK: union of all references that must survive.
            Set<ContentId> live = new HashSet<>();
            live.addAll(objects.allReferencedContentIds());
            live.addAll(snapshots.allReferencedContentIds());
            log.info("GC mark phase: liveReferences={} (in-flight writes are rechecked per blob)",
                    live.size());

            int scanned = 0;
            int deleted = 0;
            long reclaimed = 0L;

            // SWEEP: reachability is re-checked under each blob's publish lock,
            // so a blob published after the mark snapshot cannot be deleted
            // before its metadata commits.
            for (ContentId id : blobs.allBlobIds()) {
                scanned++;
                long freed = blobs.deleteIfUnreferenced(id, live);
                if (freed >= 0) {
                    deleted++;
                    reclaimed += freed;
                }
            }
            log.info("GC pass complete scanned={} deleted={} reclaimedBytes={} live={}",
                    scanned, deleted, reclaimed, live.size());
            return new GcResult(scanned, deleted, reclaimed);
        } catch (StorageException e) {
            // Reference computation is in-memory; this indicates serious trouble.
            throw new IllegalStateException("GC failed while computing live set", e);
        } finally {
            objects.lock.writeLock().unlock();
        }
    }
}

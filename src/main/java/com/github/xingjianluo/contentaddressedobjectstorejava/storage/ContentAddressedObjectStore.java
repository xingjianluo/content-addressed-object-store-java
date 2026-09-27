package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Single entry point combining content-addressed blobs, versioned objects,
 * snapshots and garbage collection on top of one on-disk root directory.
 */
public class ContentAddressedObjectStore implements AutoCloseable {

    private final BlobStore blobs;
    private final ObjectStore objects;
    private final SnapshotStore snapshots;
    private final GarbageCollector gc;

    public ContentAddressedObjectStore(Path rootDir) {
        this.blobs = new BlobStore(rootDir);
        this.objects = new ObjectStore(blobs, rootDir);
        this.snapshots = new SnapshotStore(objects, rootDir);
        this.gc = new GarbageCollector(blobs, objects, snapshots);
    }

    public ObjectVersion putIfAbsent(String objectKey, InputStream content) throws StorageException {
        return write(objectKey, content, 0, true);
    }

    public ObjectVersion putIfVersion(String objectKey, InputStream content, long expectedVersion)
            throws StorageException {
        return write(objectKey, content, expectedVersion, false);
    }

    private ObjectVersion write(String objectKey, InputStream content, long expectedVersion,
                                boolean createOnly) throws StorageException {
        // Stage+publish the blob first (pending protection on), then commit
        // metadata, then drop protection. A failure leaves only an orphan blob
        // (which GC will reclaim) and never a dangling object reference.
        ContentId id = blobs.put(content);
        try {
            long size = blobs.sizeOf(id);
            ObjectVersion result = createOnly
                    ? objects.putIfAbsent(objectKey, id, size)
                    : objects.putIfVersion(objectKey, id, size, expectedVersion);
            // Content verification on the write path: re-read what is on disk.
            blobs.verify(id);
            return result;
        } finally {
            blobs.releasePending(id);
        }
    }

    public InputStream readCurrent(String objectKey) throws StorageException {
        ObjectVersion v = objects.getCurrent(objectKey);
        return openVerified(v);
    }

    public InputStream readVersion(String objectKey, long version) throws StorageException {
        ObjectVersion v = objects.getVersion(objectKey, version);
        return openVerified(v);
    }

    private InputStream openVerified(ObjectVersion v) throws StorageException {
        try {
            return blobs.open(v.contentId());
        } catch (ChecksumMismatchException | ContentCorruptedException e) {
            throw e;
        }
    }

    public Snapshot snapshot(String name) {
        return snapshots.create(name);
    }

    public GcResult collectGarbage() {
        return gc.collect();
    }

    public ObjectStore objects() {
        return objects;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public BlobStore blobs() {
        return blobs;
    }

    @Override
    public void close() {
    }
}

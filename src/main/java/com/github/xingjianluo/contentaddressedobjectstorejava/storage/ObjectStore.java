package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Versioned object metadata over a {@link BlobStore}.
 *
 * <p>Every successful conditional write appends an immutable
 * {@link ObjectVersion}; old versions are never overwritten or lost. All
 * mutations are serialized by a write lock (providing atomic compare-and-append
 * semantics), while reads take the shared read lock. The same lock boundary is
 * used by snapshot creation and GC so those operations observe a consistent
 * state.</p>
 *
 * <p>Version convention: first version is {@code 1}. A conditional write with
 * {@code expectedVersion == 0} means "create only if absent".</p>
 */
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private final BlobStore blobs;
    private final MetadataStore metadata;
    private final Map<String, List<ObjectVersion>> objects = new ConcurrentHashMap<>();
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionRecord(long version, String contentId, long size, String createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ObjectMetadataRecord(String key, List<VersionRecord> versions) {
    }

    public ObjectStore(BlobStore blobs, Path rootDir) {
        this.blobs = blobs;
        this.metadata = new MetadataStore(rootDir);
        loadFromDisk();
    }

    private void loadFromDisk() {
        for (Path file : metadata.listObjectFiles()) {
            try {
                ObjectMetadataRecord rec = metadata.readJson(file, ObjectMetadataRecord.class);
                List<ObjectVersion> versions = new ArrayList<>();
                for (VersionRecord v : rec.versions()) {
                    versions.add(new ObjectVersion(rec.key(), v.version(),
                            ContentId.of(v.contentId()), v.size(), Instant.parse(v.createdAt())));
                }
                versions.sort((a, b) -> Long.compare(a.version(), b.version()));
                objects.put(rec.key(), versions);
            } catch (IOException e) {
                throw new IllegalStateException("corrupt object metadata file: " + file, e);
            }
        }
        log.info("ObjectStore loaded {} object(s) from disk", objects.size());
    }

    /** Create version 1 only when the key is absent. */
    public ObjectVersion putIfAbsent(String objectKey, ContentId contentId, long size)
            throws StorageException {
        return conditionalPut(objectKey, contentId, size, 0, true);
    }

    /**
     * Append version {@code expectedVersion+1} only if the current version
     * equals {@code expectedVersion} ({@code 0} means the key must be absent).
     *
     * @throws VersionConflictException if the condition is not met or a race is lost
     */
    public ObjectVersion putIfVersion(String objectKey, ContentId contentId, long size,
                                      long expectedVersion) throws StorageException {
        return conditionalPut(objectKey, contentId, size, expectedVersion, false);
    }

    private ObjectVersion conditionalPut(String objectKey, ContentId contentId, long size,
                                         long expectedVersion, boolean createOnly)
            throws StorageException {
        lock.writeLock().lock();
        try {
            List<ObjectVersion> history = objects.get(objectKey);
            long current = history == null ? 0 : history.get(history.size() - 1).version();

            if (createOnly && current != 0) {
                log.warn("putIfAbsent REJECTED key='{}' reason=already-exists currentVersion={} expected=0",
                        objectKey, current);
                throw new VersionConflictException(
                        "object already exists: key=" + objectKey + ", currentVersion=" + current);
            }
            if (!createOnly && current != expectedVersion) {
                log.warn("conditional put REJECTED key='{}' reason=version-mismatch expected={} actual={} content={}",
                        objectKey, expectedVersion, current, contentId.sha256Hex());
                throw new VersionConflictException(
                        "version conflict: key=" + objectKey + ", expectedVersion=" + expectedVersion
                                + ", actualVersion=" + current);
            }

            long newVersion = current + 1;
            ObjectVersion ov = new ObjectVersion(objectKey, newVersion, contentId, size, Instant.now());

            // Persist BEFORE mutating in-memory state: a failed write leaves no
            // observable intermediate state.
            List<VersionRecord> records = new ArrayList<>();
            List<ObjectVersion> base = history == null ? List.of() : history;
            for (ObjectVersion v : base) {
                records.add(new VersionRecord(v.version(), v.contentId().sha256Hex(),
                        v.size(), v.createdAt().toString()));
            }
            records.add(new VersionRecord(ov.version(), ov.contentId().sha256Hex(),
                    ov.size(), ov.createdAt().toString()));
            try {
                metadata.writeJson(metadata.objectFile(objectKey),
                        new ObjectMetadataRecord(objectKey, records));
            } catch (UncheckedIOException e) {
                log.error("conditional put FAILED key='{}' reason=metadata-persist-failure content={}",
                        objectKey, contentId.sha256Hex(), e);
                throw new MetadataPersistenceException(
                        "failed persisting metadata for key=" + objectKey, e.getCause());
            }

            List<ObjectVersion> updated = new ArrayList<>(base);
            updated.add(ov);
            objects.put(objectKey, Collections.unmodifiableList(updated));
            log.info("conditional put ACCEPTED key='{}' version={} prevVersion={} content={} size={}",
                    objectKey, newVersion, current, contentId.sha256Hex(), size);
            return ov;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ObjectVersion getCurrent(String objectKey) throws StorageException {
        return withReadLock(() -> {
            List<ObjectVersion> history = objects.get(objectKey);
            if (history == null || history.isEmpty()) {
                log.warn("getCurrent REJECTED key='{}' reason=not-found", objectKey);
                throw new ObjectNotFoundException("object not found: key=" + objectKey);
            }
            return history.get(history.size() - 1);
        });
    }

    public ObjectVersion getVersion(String objectKey, long version) throws StorageException {
        return withReadLock(() -> {
            List<ObjectVersion> history = objects.get(objectKey);
            if (history == null) {
                throw new ObjectNotFoundException(
                        "object not found: key=" + objectKey + ", requestedVersion=" + version);
            }
            for (ObjectVersion v : history) {
                if (v.version() == version) {
                    return v;
                }
            }
            throw new ObjectNotFoundException(
                    "version not found: key=" + objectKey + ", requestedVersion=" + version
                            + ", currentVersion=" + history.get(history.size() - 1).version());
        });
    }

    /** @return immutable defensive copy of the version history (oldest first); empty if absent */
    public List<ObjectVersion> history(String objectKey) throws StorageException {
        return withReadLock(() -> {
            List<ObjectVersion> history = objects.get(objectKey);
            return history == null ? List.of() : List.copyOf(history);
        });
    }

    /** Delete an object's metadata (its blobs stay until GC unless pinned elsewhere). */
    public Optional<ObjectVersion> delete(String objectKey) {
        lock.writeLock().lock();
        try {
            List<ObjectVersion> history = objects.remove(objectKey);
            metadata.deleteObjectFile(objectKey);
            if (history == null) {
                log.info("delete key='{}' result=absent", objectKey);
                return Optional.empty();
            }
            ObjectVersion last = history.get(history.size() - 1);
            log.info("delete key='{}' result=removed lastVersion={} versionsRemoved={}",
                    objectKey, last.version(), history.size());
            return Optional.of(last);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Every blob id referenced by ANY retained version of ANY object. */
    public Set<ContentId> allReferencedContentIds() throws StorageException {
        return withReadLock(() -> {
            Set<ContentId> ids = new HashSet<>();
            for (List<ObjectVersion> history : objects.values()) {
                for (ObjectVersion v : history) {
                    ids.add(v.contentId());
                }
            }
            return ids;
        });
    }

    /** Package-internal view of authoritative state; callers MUST hold a lock. */
    Map<String, List<ObjectVersion>> rawView() {
        return objects;
    }

    <T> T withReadLock(ThrowingSupplier<T> action) throws StorageException {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    void withReadLock(Runnable action) {
        lock.readLock().lock();
        try {
            action.run();
        } finally {
            lock.readLock().unlock();
        }
    }

    <T> T withWriteLock(ThrowingSupplier<T> action) throws StorageException {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content-addressed blob storage.
 *
 * <pre>
 *   root/
 *     blobs/&lt;xx&gt;/&lt;full sha256 hex&gt;   published immutable blobs
 *     tmp/upload-&lt;uuid&gt;.tmp          staged, not-yet-published bytes
 * </pre>
 *
 * <p>Writes are staged, verified by digest and then atomically moved into
 * place; identical content collapses onto a single physical file. A pending
 * registry protects blobs that are already published but whose object
 * metadata commit has not finished yet: concurrent GC treats them as live.</p>
 */
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    private final Path rootDir;
    private final Path blobsDir;
    private final Path tmpDir;

    /** Content ids of writes published to blobs/ but not yet committed as metadata. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** Per-id locks so duplicate concurrent uploads publish exactly one file. */
    private final Map<String, Object> publishLocks = new ConcurrentHashMap<>();

    public BlobStore(Path rootDir) {
        try {
            this.rootDir = rootDir;
            this.blobsDir = rootDir.resolve("blobs");
            this.tmpDir = rootDir.resolve("tmp");
            Files.createDirectories(blobsDir);
            Files.createDirectories(tmpDir);
            int removed = cleanupStaging();
            log.info("BlobStore opened root={} (cleaned {} abandoned staging files)", rootDir, removed);
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialize blob store at " + rootDir, e);
        }
    }

    public Path root() {
        return rootDir;
    }

    /**
     * Stage, hash, verify and atomically publish the bytes. Duplicate content
     * is deduplicated. The returned id stays in the pending-protection set
     * until {@link #releasePending(ContentId)} is called after metadata commit.
     */
    public ContentId put(InputStream in) throws StorageException {
        Path staged;
        String sha;
        long size;
        try {
            staged = Files.createTempFile(tmpDir, "upload-", ".tmp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new BufferedInputStream(in);
                 DigestOutputStream out = new DigestOutputStream(
                         Files.newOutputStream(staged), md)) {
                source.transferTo(out);
            }
            sha = HexFormat.toHex(md.digest());
            size = Files.size(staged);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new ContentCorruptedException("failed staging upload to " + tmpDir, e);
        }

        ContentId id = ContentId.of(sha);
        Path target = blobPath(id);
        Object lock = publishLocks.computeIfAbsent(sha, k -> new Object());
        try {
            synchronized (lock) {
                if (Files.exists(target)) {
                    deleteQuietly(staged);
                    // Existing published file is content-addressed, hence trusted;
                    // still protect it until the caller commits metadata.
                    pending.add(sha);
                    log.info("dedup hit content={} size={} (blob already published)", sha, size);
                    return id;
                }
                // Register BEFORE the atomic move: any GC pass that observes the
                // blob must also see it protected.
                pending.add(sha);
                try {
                    Files.createDirectories(target.getParent());
                    try {
                        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(staged, target);
                    }
                } catch (IOException e) {
                    pending.remove(sha);
                    if (Files.exists(target)) {
                        // A concurrent writer won the race: that is a dedup hit.
                        deleteQuietly(staged);
                        return id;
                    }
                    throw new ContentCorruptedException("failed publishing blob " + sha, e);
                }
                log.info("published new content={} size={} path={}", sha, size, target);
                return id;
            }
        } finally {
            publishLocks.remove(sha);
        }
    }

    /** Physical size in bytes of a published blob. */
    public long sizeOf(ContentId id) throws ContentCorruptedException {
        Path path = blobPath(id);
        try {
            if (!Files.isRegularFile(path)) {
                throw new ContentCorruptedException(
                        "blob missing on disk: content=" + id.sha256Hex(), null);
            }
            return Files.size(path);
        } catch (IOException e) {
            throw new ContentCorruptedException("cannot stat blob: " + id.sha256Hex(), e);
        }
    }

    /** Invoked after object metadata has committed; drops GC protection for an upload. */
    public void releasePending(ContentId id) {
        if (pending.remove(id.sha256Hex())) {
            log.info("released pending protection for content={}", id.sha256Hex());
        }
    }

    /**
     * Open a blob for reading. The returned stream re-hashes every byte and
     * throws {@link DigestMismatchIOException} at EOF on mismatch.
     */
    public InputStream open(ContentId id) throws StorageException {
        Path path = requireBlobPath(id);
        if (!Files.isRegularFile(path)) {
            log.warn("read rejected: blob missing on disk content={} path={}", id.sha256Hex(), path);
            throw new ContentCorruptedException(
                    "blob missing on disk: content=" + id.sha256Hex(), null);
        }
        try {
            long size = Files.size(path);
            InputStream raw = new BufferedInputStream(Files.newInputStream(path));
            return new VerifyingInputStream(raw, id.sha256Hex(), size);
        } catch (NoSuchFileException e) {
            throw new ContentCorruptedException("blob vanished before open: " + id.sha256Hex(), e);
        } catch (IOException e) {
            throw new ContentCorruptedException("cannot open blob: " + id.sha256Hex(), e);
        }
    }

    /** Fully read a blob and verify it matches its content id. */
    public void verify(ContentId id) throws StorageException {
        try (InputStream in = open(id)) {
            in.transferTo(NullOutputStream.INSTANCE);
        } catch (DigestMismatchIOException e) {
            log.warn("verify FAILED checksum content={} expected={} actual={}",
                    id.sha256Hex(), e.expectedHash(), e.actualHash());
            throw new ChecksumMismatchException(e.expectedHash(), e.actualHash());
        } catch (IOException e) {
            log.warn("verify FAILED physical corruption content={}: {}", id.sha256Hex(), e.toString());
            throw new ContentCorruptedException("unreadable blob: " + id.sha256Hex(), e);
        }
    }

    /** All published blob ids currently present under blobs/. */
    public Set<ContentId> allBlobIds() {
        Set<ContentId> ids = new HashSet<>();
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(blobsDir)) {
            for (Path shard : shards) {
                if (!Files.isDirectory(shard)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (Path f : files) {
                        String name = f.getFileName().toString();
                        if (name.length() == 64 && HexFormat.isHex(name) && Files.isRegularFile(f)) {
                            ids.add(ContentId.of(name));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot enumerate blobs under " + blobsDir, e);
        }
        return ids;
    }

    /** Ids of writes in flight between publish and metadata commit (GC protection). */
    public Set<ContentId> pendingIds() {
        Set<ContentId> ids = new HashSet<>();
        for (String sha : pending) {
            ids.add(ContentId.of(sha));
        }
        return ids;
    }

    /** Delete a blob if present. @return number of bytes freed */
    public long deleteIfExists(ContentId id) {
        synchronized (publishLockFor(id.sha256Hex())) {
            return doDelete(id);
        }
    }

    /**
     * GC sweep primitive. Re-evaluates reachability while holding the same
     * per-id lock used by {@link #put}, closing the window in which a writer
     * publishes a blob and registers pending protection after GC took its
     * mark-phase snapshot.
     *
     * @return bytes freed, or {@code -1} when the blob must be preserved
     *         (committed reference, or a write in flight)
     */
    public long deleteIfUnreferenced(ContentId id, Set<ContentId> liveReferences) {
        String sha = id.sha256Hex();
        synchronized (publishLockFor(sha)) {
            if (liveReferences.contains(id)) {
                return -1L;
            }
            if (pending.contains(sha)) {
                log.info("GC preserves in-flight content={} (pending rechecked under publish lock)",
                        sha);
                return -1L;
            }
            return doDelete(id);
        }
    }

    private Object publishLockFor(String sha) {
        return publishLocks.computeIfAbsent(sha, k -> new Object());
    }

    private long doDelete(ContentId id) {
        Path path = blobPath(id);
        try {
            long size = Files.exists(path) ? Files.size(path) : 0L;
            if (Files.deleteIfExists(path)) {
                log.info("GC deleted unreferenced content={} bytes={}", id.sha256Hex(), size);
                return size;
            }
            return 0L;
        } catch (IOException e) {
            log.warn("GC could not delete content={}: {}", id.sha256Hex(), e.toString());
            return 0L;
        }
    }

    /** Remove abandoned staged uploads (e.g. left by a crashed JVM). @return count removed */
    public int cleanupStaging() {
        int removed = 0;
        if (!Files.isDirectory(tmpDir)) {
            return removed;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(tmpDir, "upload-*.tmp")) {
            for (Path f : files) {
                try {
                    if (Files.deleteIfExists(f)) {
                        removed++;
                    }
                } catch (IOException ignored) {
                    // best effort
                }
            }
        } catch (IOException e) {
            log.warn("staging cleanup failed: {}", e.toString());
        }
        if (removed > 0) {
            log.info("removed {} abandoned staging file(s)", removed);
        }
        return removed;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("could not delete staging file {}: {}", p, e.toString());
        }
    }

    private Path blobPath(ContentId id) {
        String sha = id.sha256Hex();
        if (sha.length() != 64 || !HexFormat.isHex(sha)) {
            throw new IllegalArgumentException("illegal content id: " + sha);
        }
        return blobsDir.resolve(sha.substring(0, 2)).resolve(sha);
    }

    private Path requireBlobPath(ContentId id) {
        return blobPath(id);
    }
}

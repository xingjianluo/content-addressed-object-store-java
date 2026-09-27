package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.exception.ContentCorruptedException;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于文件系统的内容寻址 blob 存储实现。
 *
 * <p>写入分两阶段：{@link #stage(byte[])} 把内容放进 staging 并算出哈希；
 * {@link #commit(StagedBlob)} 通过同分区原子 rename 发布到 blobs 分片目录。
 * 相同哈希只保留一份物理副本（rename 撞名时丢弃 staging 文件）。
 * 读取时重新计算哈希并与文件名比对，发现物理损坏立即拒绝。</p>
 */
public class FileBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileBlobStore.class);

    private final StoreLayout layout;

    public FileBlobStore(StoreLayout layout) {
        this.layout = layout;
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(layout.blobsDir());
        Files.createDirectories(layout.stagingDir());
    }

    @Override
    public StagedBlob stage(byte[] content) throws IOException {
        ContentHash hash = Hashing.sha256(content);
        Path tmp = layout.newStagingFile();
        Files.write(tmp, content);
        log.debug("[blob] staged hash={} length={} tmp={}", hash.value(), content.length, tmp);
        return new StagedBlob(hash, tmp, content.length);
    }

    @Override
    public void commit(StagedBlob staged) throws IOException {
        Path target = layout.blobPath(staged.hash());
        if (Files.exists(target)) {
            // 内容已存在：物理去重，丢弃 staging 副本
            Files.deleteIfExists(staged.tempPath());
            log.debug("[blob] commit dedup hit hash={} path={}", staged.hash().value(), target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path publishTmp = target.resolveSibling(".pub-" + target.getFileName());
        // 先复制到目标分片目录（rename 必须跨目录原子），再原子改名
        Files.copy(staged.tempPath(), publishTmp);
        try {
            try {
                Files.move(publishTmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                log.debug("[blob] commit published hash={} path={}", staged.hash().value(), target);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(publishTmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(publishTmp);
            Files.deleteIfExists(staged.tempPath());
        }
    }

    @Override
    public void abort(StagedBlob staged) throws IOException {
        boolean deleted = Files.deleteIfExists(staged.tempPath());
        log.debug("[blob] abort staged hash={} deleted={}", staged.hash().value(), deleted);
    }

    @Override
    public byte[] readVerified(ContentHash hash) throws IOException {
        Path path = layout.blobPath(hash);
        if (!Files.exists(path)) {
            throw new ContentCorruptedException(hash,
                    "blob file missing at " + path);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ContentCorruptedException(hash,
                    "blob file unreadable at " + path, e);
        }
        ContentHash actual = Hashing.sha256(bytes);
        if (!actual.equals(hash)) {
            // 文件名（内容寻址 ID）与实算内容不符 => 物理损坏/位腐烂
            log.warn("[blob] CORRUPTION detected id={} actual={} path={}",
                    hash.value(), actual.value(), path);
            throw new ContentCorruptedException(hash,
                    "recomputed hash " + actual.value()
                            + " does not match content-addressed file name");
        }
        return bytes;
    }

    @Override
    public boolean exists(ContentHash hash) {
        return Files.exists(layout.blobPath(hash));
    }

    @Override
    public long sizeOf(ContentHash hash) throws IOException {
        Path p = layout.blobPath(hash);
        return Files.exists(p) ? Files.size(p) : -1L;
    }

    @Override
    public Set<ContentHash> listCommitted() throws IOException {
        if (!Files.exists(layout.blobsDir())) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(layout.blobsDir())) {
            return walk.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.length() == ContentHash.HASH_LENGTH)
                    .map(ContentHash::new)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public boolean delete(ContentHash hash) throws IOException {
        Path path = layout.blobPath(hash);
        boolean removed = Files.deleteIfExists(path);
        log.debug("[blob] gc delete hash={} removed={}", hash.value(), removed);
        return removed;
    }

    @Override
    public long totalCommittedBytes() throws IOException {
        if (!Files.exists(layout.blobsDir())) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(layout.blobsDir())) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sum();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

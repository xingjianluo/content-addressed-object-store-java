package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 未完成写入登记表的文件实现。
 *
 * <p>每条进行中的写入对应 journal/ 下一个小 JSON 文件（原子写入），
 * 记录其引用的内容哈希。GC 把这些哈希视为根，保证“写入中”内容不被回收。
 * 写入流程在 stage 之后、version commit 之前 begin，version commit 之后 complete。</p>
 */
public class FilePendingWriteJournal implements PendingWriteJournal {

    private static final Logger log = LoggerFactory.getLogger(FilePendingWriteJournal.class);

    private final StoreLayout layout;

    public FilePendingWriteJournal(StoreLayout layout) {
        this.layout = layout;
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(layout.journalDir());
    }

    @Override
    public String begin(String objectKey, ContentHash hash) throws IOException {
        String entryId = UUID.randomUUID().toString();
        Entry entry = new Entry(entryId, objectKey, hash.value(), System.currentTimeMillis());
        StoreLayout.writeAtomically(layout.journalPath(entryId), JsonCodec.write(entry));
        log.info("[journal] BEGIN pending-write id={} object='{}' hash={}",
                entryId, objectKey, hash.value());
        return entryId;
    }

    @Override
    public void complete(String entryId) throws IOException {
        boolean removed = Files.deleteIfExists(layout.journalPath(entryId));
        log.info("[journal] COMPLETE pending-write id={} removed={}", entryId, removed);
    }

    @Override
    public void abort(String entryId) throws IOException {
        boolean removed = Files.deleteIfExists(layout.journalPath(entryId));
        log.info("[journal] ABORT pending-write id={} removed={}", entryId, removed);
    }

    @Override
    public Set<ContentHash> referencedHashes() throws IOException {
        if (!Files.exists(layout.journalDir())) {
            return Set.of();
        }
        Set<ContentHash> refs = new HashSet<>();
        try (Stream<Path> files = Files.list(layout.journalDir())) {
            List<Path> entries = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : entries) {
                try {
                    Entry entry = JsonCodec.read(Files.readAllBytes(file), Entry.class);
                    if (entry.hash != null) {
                        refs.add(ContentHash.of(entry.hash));
                    }
                } catch (RuntimeException e) {
                    // 损坏/半写条目跳过而不是让 GC 整体失败（该条目对应的 staging 本就不发布）
                    log.warn("[journal] ignore unreadable entry {}: {}", file, e.toString());
                }
            }
        }
        log.debug("[journal] referenced hashes from pending writes: {}", refs.size());
        return Collections.unmodifiableSet(refs);
    }

    /** 日志条目 JSON 结构。 */
    public static final class Entry {
        @JsonProperty("id")
        public String id;
        @JsonProperty("objectKey")
        public String objectKey;
        @JsonProperty("hash")
        public String hash;
        @JsonProperty("startedAt")
        public long startedAt;

        public Entry() {
        }

        public Entry(String id, String objectKey, String hash, long startedAt) {
            this.id = id;
            this.objectKey = objectKey;
            this.hash = hash;
            this.startedAt = startedAt;
        }
    }
}

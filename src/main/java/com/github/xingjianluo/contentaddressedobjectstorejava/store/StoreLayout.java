package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 磁盘布局与文件工具。
 *
 * <pre>
 * root/
 *   blobs/ab/cd/abcdef...fullhash      # 不可变内容，按哈希前 4 位两级分片
 *   staging/                           # 写入中途的临时内容（GC 不视为可达）
 *   objects/&lt;urlencoded-key&gt/meta.json  # 对象指针：当前版本号
 *   objects/&lt;key&gt;/v&lt;n&gt;.json            # 不可变版本记录
 *   snapshots/&lt;snapshotId&gt;.json         # 快照映射
 *   journal/&lt;entryId&gt;.json             # 未完成写入登记
 * </pre>
 */
public final class StoreLayout {

    private final Path root;
    private final Path blobsDir;
    private final Path stagingDir;
    private final Path objectsDir;
    private final Path snapshotsDir;
    private final Path journalDir;

    public StoreLayout(Path root) {
        this.root = root;
        this.blobsDir = root.resolve("blobs");
        this.stagingDir = root.resolve("staging");
        this.objectsDir = root.resolve("objects");
        this.snapshotsDir = root.resolve("snapshots");
        this.journalDir = root.resolve("journal");
    }

    public Path root() {
        return root;
    }

    public Path blobsDir() {
        return blobsDir;
    }

    public Path stagingDir() {
        return stagingDir;
    }

    public Path objectsDir() {
        return objectsDir;
    }

    public Path snapshotsDir() {
        return snapshotsDir;
    }

    public Path journalDir() {
        return journalDir;
    }

    /** 内容文件路径：blobs/ab/cd/&lt;fullhash&gt;。 */
    public Path blobPath(ContentHash hash) {
        String h = hash.value();
        return blobsDir.resolve(h.substring(0, 2))
                .resolve(h.substring(2, 4))
                .resolve(h);
    }

    public Path objectDir(String objectKey) {
        return objectsDir.resolve(encodeKey(objectKey));
    }

    public Path objectMetaPath(String objectKey) {
        return objectDir(objectKey).resolve("meta.json");
    }

    public Path versionRecordPath(String objectKey, long version) {
        return objectDir(objectKey).resolve("v" + version + ".json");
    }

    public Path snapshotPath(String snapshotId) {
        return snapshotsDir.resolve(snapshotId + ".json");
    }

    public Path journalPath(String entryId) {
        return journalDir.resolve(entryId + ".json");
    }

    public Path newStagingFile() {
        return stagingDir.resolve("stg-" + UUID.randomUUID() + ".tmp");
    }

    /** 对象名编码：仅保留安全字符，其余做百分号编码，保证可作文件名。 */
    public static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length() * 2);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /** 在同目录下创建临时文件并原子移动到 target（同分区，rename 原子）。 */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path tmp = parent.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, data, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 退化为非原子替换（同目录仍尽量保证崩溃安全）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}

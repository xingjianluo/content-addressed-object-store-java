package com.github.xingjianluo.contentaddressedobjectstorejava.tests;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.Hashing;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.ObjectStoreService;
import com.github.xingjianluo.contentaddressedobjectstorejava.store.StoreLayout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 测试公共辅助：临时目录中的存储与文件损坏手段。 */
abstract class StoreTestSupport {

    @TempDir
    Path tempDir;

    ObjectStoreService newStore() throws IOException {
        ObjectStoreService store = new ObjectStoreService(tempDir.resolve("store"));
        store.init();
        return store;
    }

    /** 另一根目录，用于多实例/竞争场景。 */
    ObjectStoreService newStore(String sub) throws IOException {
        ObjectStoreService store = new ObjectStoreService(tempDir.resolve(sub));
        store.init();
        return store;
    }

    static ContentHash hashOf(byte[] content) {
        return Hashing.sha256(content);
    }

    /** 定位某内容哈希对应的物理 blob 文件：root/blobs/ab/cd/<hash>。 */
    Path blobPath(ObjectStoreService store, ContentHash hash) {
        String hv = hash.value();
        Path path = store.rootDir().resolve("blobs")
                .resolve(hv.substring(0, 2)).resolve(hv.substring(2, 4)).resolve(hv);
        if (!Files.exists(path)) {
            throw new IllegalStateException("blob not found: " + path);
        }
        return path;
    }

    /** 覆写 blob 文件内容模拟物理位腐烂（文件名不变）。 */
    void corruptBlobPhysically(Path blobFile, byte[] garbage) throws IOException {
        Files.write(blobFile, garbage, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 直接在对象版本记录 JSON 中替换哈希，模拟元数据与内容寻址 ID 不一致。 */
    void tamperVersionRecordHash(ObjectStoreService store, String objectKey,
                                 long version, String badHash) throws IOException {
        Path root = storeRoot(store);
        Path record = root.resolve("objects")
                .resolve(StoreLayout.encodeKey(objectKey))
                .resolve("v" + version + ".json");
        String json = Files.readString(record);
        // 记录形如 {"version":0,"hash":"<64hex>",...}，替换第一段 64 位十六进制
        String tampered = json.replaceFirst("[0-9a-f]{64}", badHash);
        Files.writeString(record, tampered, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 直接篡改对象某版本记录 JSON 中的 length 字段（哈希不变，物理内容完好）。 */
    void tamperVersionRecordLength(ObjectStoreService store, String objectKey,
                                   long version, long badLength) throws IOException {
        Path root = storeRoot(store);
        Path record = root.resolve("objects")
                .resolve(StoreLayout.encodeKey(objectKey))
                .resolve("v" + version + ".json");
        String json = Files.readString(record);
        String tampered = json.replaceFirst("\"length\":\\d+", "\"length\":" + badLength);
        Files.writeString(record, tampered, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 同时改写 meta 指针中的哈希，使 head/read 读取走被篡改的登记哈希。 */
    void tamperMetaHash(ObjectStoreService store, String objectKey, String badHash)
            throws IOException {
        Path root = storeRoot(store);
        Path meta = root.resolve("objects")
                .resolve(StoreLayout.encodeKey(objectKey))
                .resolve("meta.json");
        String json = Files.readString(meta);
        String tampered = json.replaceFirst("[0-9a-f]{64}", badHash);
        Files.writeString(meta, tampered, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Path storeRoot(ObjectStoreService store) {
        return store.rootDir();
    }

    static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}

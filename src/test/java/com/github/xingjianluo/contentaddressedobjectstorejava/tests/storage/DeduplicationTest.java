package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentId;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identical content must collapse to one physical blob, while different
 * objects/versions stay independently addressable.
 */
class DeduplicationTest {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationTest.class);

    private static byte[] readAll(InputStream in) throws Exception {
        return in.readAllBytes();
    }

    private static long blobFileCount(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root.resolve("blobs"))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void identicalContentIsDeduplicatedAcrossObjectsAndVersions(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            byte[] payload = "hello-content-addressed-world".getBytes(StandardCharsets.UTF_8);
            byte[] other = "different payload entirely".getBytes(StandardCharsets.UTF_8);

            // Two distinct objects carrying identical content.
            ObjectVersion a1 = store.putIfAbsent("object-a", new ByteArrayInputStream(payload));
            ObjectVersion b1 = store.putIfAbsent("object-b", new ByteArrayInputStream(payload));
            log.info("[dedup] a1={} b1={} sameContent={}",
                    a1.contentId(), b1.contentId(), a1.contentId().equals(b1.contentId()));
            assertEquals(a1.contentId(), b1.contentId(),
                    "same bytes must map to the same content id");

            // Independently addressable, contents intact.
            assertArrayEquals(payload, readAll(store.readCurrent("object-a")));
            assertArrayEquals(payload, readAll(store.readVersion("object-b", 1)));

            // One new version on object-a with different content -> second blob.
            ObjectVersion a2 = store.putIfVersion("object-a",
                    new ByteArrayInputStream(other), a1.version());
            assertNotEquals(a1.contentId(), a2.contentId());
            log.info("[dedup] a2={} (new content) -> expect 2 blobs", a2.contentId());

            // Writing the old content again (object-c) must NOT create a blob.
            long before = blobFileCount(tmp);
            ObjectVersion c1 = store.putIfAbsent("object-c", new ByteArrayInputStream(payload));
            long after = blobFileCount(tmp);
            log.info("[dedup] c1={} blobsBefore={} blobsAfter={}", c1.contentId(), before, after);
            assertEquals(before, after, "repeat content must not allocate a new blob");
            assertEquals(2L, after, "two unique payloads => two physical blobs");

            // All versions independently addressable; history preserved.
            List<ObjectVersion> history = store.objects().history("object-a");
            assertEquals(2, history.size());
            assertArrayEquals(payload, readAll(store.readVersion("object-a", 1)));
            assertArrayEquals(other, readAll(store.readVersion("object-a", 2)));
            log.info("[dedup] history of object-a = {} versions, both readable", history.size());
            assertTrue(history.get(0).createdAt().isBefore(history.get(1).createdAt())
                    || history.get(0).createdAt().equals(history.get(1).createdAt()));
        }
    }

    @Test
    void sameContentDifferentObjectsHaveDistinctKeysAndSharedContent(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            byte[] payload = "shared".getBytes(StandardCharsets.UTF_8);
            ContentId id = store.putIfAbsent("k1", new ByteArrayInputStream(payload)).contentId();
            for (int i = 2; i <= 50; i++) {
                ObjectVersion v = store.putIfAbsent("k" + i, new ByteArrayInputStream(payload));
                assertEquals(id, v.contentId());
            }
            assertEquals(1L, blobFileCount(tmp));
            log.info("[dedup] 50 objects sharing one payload -> exactly 1 blob on disk");
        }
    }
}

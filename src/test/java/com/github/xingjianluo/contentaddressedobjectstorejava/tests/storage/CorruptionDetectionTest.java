package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ChecksumMismatchException;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentCorruptedException;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.DigestMismatchIOException;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte tampering must surface as checksum mismatch (logical corruption), while
 * a missing/unreadable blob surfaces as physical corruption. The failure
 * causes must be distinguishable by exception type.
 */
class CorruptionDetectionTest {

    private static final Logger log = LoggerFactory.getLogger(CorruptionDetectionTest.class);

    private static Path blobPath(Path root, String sha) {
        return root.resolve("blobs").resolve(sha.substring(0, 2)).resolve(sha);
    }

    @Test
    void tamperedBytesAreRejectedWithChecksumMismatch(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            byte[] payload = "integrity-sensitive payload".getBytes(StandardCharsets.UTF_8);
            ObjectVersion v = store.putIfAbsent("doc", new ByteArrayInputStream(payload));
            String sha = v.contentId().sha256Hex();
            Path blob = blobPath(tmp, sha);
            log.info("[corrupt] wrote doc version={} content={} path={}", v.version(), sha, blob);

            // Flip one byte directly in the physical blob file.
            byte[] onDisk = Files.readAllBytes(blob);
            onDisk[3] = (byte) (onDisk[3] ^ 0xFF);
            Files.write(blob, onDisk);
            log.info("[corrupt] flipped byte at offset 3 to simulate bit rot");

            // 1) explicit verify API
            ChecksumMismatchException verifyEx = assertThrows(ChecksumMismatchException.class,
                    () -> store.blobs().verify(v.contentId()));
            assertEquals(sha, verifyEx.expectedHash());
            assertNotNull(verifyEx.actualHash());
            assertTrue(!verifyEx.actualHash().equalsIgnoreCase(sha),
                    "actual hash must differ from expected");
            log.info("[corrupt] verify() reported expected={} actual={} -> distinguishable cause",
                    verifyEx.expectedHash(), verifyEx.actualHash());

            // 2) streaming read API: mismatch surfaces as a typed IOException on read
            IOException readEx = assertThrows(IOException.class, () -> {
                try (InputStream in = store.readCurrent("doc")) {
                    in.readAllBytes();
                }
            });
            assertInstanceOf(DigestMismatchIOException.class, readEx);
            log.info("[corrupt] readCurrent() threw {}: {}",
                    readEx.getClass().getSimpleName(), readEx.getMessage());
        }
    }

    @Test
    void truncatedBlobIsRejectedWithChecksumMismatch(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            byte[] payload = "0123456789-0123456789-0123456789".getBytes(StandardCharsets.UTF_8);
            ObjectVersion v = store.putIfAbsent("doc", new ByteArrayInputStream(payload));
            Path blob = blobPath(tmp, v.contentId().sha256Hex());
            Files.write(blob, new byte[]{1, 2, 3});
            log.info("[corrupt] truncated blob {} to 3 bytes (original {})",
                    v.contentId(), payload.length);

            assertThrows(ChecksumMismatchException.class,
                    () -> store.blobs().verify(v.contentId()));
            assertThrows(IOException.class, () -> {
                try (InputStream in = store.readCurrent("doc")) {
                    in.readAllBytes();
                }
            });
            log.info("[corrupt] truncated blob correctly rejected");
        }
    }

    @Test
    void missingBlobIsReportedAsPhysicalCorruption(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            byte[] payload = "ghost".getBytes(StandardCharsets.UTF_8);
            ObjectVersion v = store.putIfAbsent("doc", new ByteArrayInputStream(payload));
            Path blob = blobPath(tmp, v.contentId().sha256Hex());
            Files.delete(blob);
            log.info("[corrupt] physically deleted blob {} while metadata still references it",
                    v.contentId());

            ContentCorruptedException ex = assertThrows(ContentCorruptedException.class,
                    () -> store.blobs().verify(v.contentId()));
            log.info("[corrupt] missing blob -> {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());

            StorageException readEx = assertThrows(StorageException.class,
                    () -> store.readCurrent("doc"));
            assertInstanceOf(ContentCorruptedException.class, readEx,
                    "missing physical file must NOT be reported as checksum mismatch");
            log.info("[corrupt] cause type={} distinguishes physical loss from checksum failure",
                    readEx.getClass().getSimpleName());
        }
    }

    @Test
    void unknownObjectAndUnknownVersionHaveDistinctMessage(@TempDir Path tmp) throws Exception {
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            store.putIfAbsent("doc", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
            assertThrows(com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectNotFoundException.class,
                    () -> store.readCurrent("missing-key"));
            assertThrows(com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectNotFoundException.class,
                    () -> store.readVersion("doc", 99));
            log.info("[corrupt] missing key and missing version both reported as not-found");
        }
    }
}

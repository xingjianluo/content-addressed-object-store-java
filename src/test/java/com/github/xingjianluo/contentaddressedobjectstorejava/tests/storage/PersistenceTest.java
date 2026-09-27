package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ContentAddressedObjectStore;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.ObjectVersion;
import com.github.xingjianluo.contentaddressedobjectstorejava.storage.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Metadata + blobs must survive store reopening: history and snapshots reload. */
class PersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceTest.class);

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void objectHistoryAndSnapshotsSurviveReopen(@TempDir Path tmp) throws Exception {
        String snapId;
        try (ContentAddressedObjectStore store = new ContentAddressedObjectStore(tmp)) {
            ObjectVersion v1 = store.putIfAbsent("doc", bytes("persisted-v1"));
            store.putIfAbsent("other", bytes("other-object"));
            Snapshot snap = store.snapshot("checkpoint"); // doc still at v1
            store.putIfVersion("doc", bytes("persisted-v2"), v1.version());
            snapId = snap.snapshotId();
            log.info("[persist] wrote 2 versions + 1 snapshot {} before close", snapId);
        }

        try (ContentAddressedObjectStore reopened = new ContentAddressedObjectStore(tmp)) {
            List<ObjectVersion> history = reopened.objects().history("doc");
            assertEquals(2, history.size());
            assertEquals("persisted-v1",
                    new String(reopened.readVersion("doc", 1).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("persisted-v2",
                    new String(reopened.readCurrent("doc").readAllBytes(), StandardCharsets.UTF_8));

            Snapshot snap = reopened.snapshots().get(snapId);
            assertEquals(1L, snap.versions().get("doc").version());
            assertEquals(history.get(0).contentId(), snap.versions().get("doc").contentId());
            assertEquals("other-object",
                    new String(reopened.readCurrent("other").readAllBytes(), StandardCharsets.UTF_8));
            log.info("[persist] after reopen: history={} versions, snapshot {} restored, blobs verified",
                    history.size(), snapId);

            // GC on reopened state must not reclaim anything live.
            assertEquals(0, reopened.collectGarbage().deletedBlobs());
            log.info("[persist] GC after reopen deleted 0 blobs (state consistent)");
        }
    }
}

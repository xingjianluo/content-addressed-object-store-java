package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import java.time.Instant;
import java.util.Map;

/**
 * An immutable point-in-time capture. Each entry records the object's version
 * number AND its content id, so a snapshot pins blobs directly even after the
 * referenced objects are deleted or rewritten.
 *
 * @param versions object key -> immutable entry (version + content + size)
 */
public record Snapshot(String snapshotId, Instant createdAt, Map<String, Entry> versions) {

    /** One captured object position within a snapshot. */
    public record Entry(long version, ContentId contentId, long size) {
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/**
 * One immutable version of an object.
 */
public record ObjectVersion(String objectKey, long version, ContentId contentId, long size,
                            java.time.Instant createdAt) {
}

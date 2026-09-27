package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/**
 * Summary of one garbage-collection pass.
 */
public record GcResult(int scannedBlobs, int deletedBlobs, long reclaimedBytes) {
}

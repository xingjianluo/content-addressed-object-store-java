package com.github.xingjianluo.contentaddressedobjectstorejava.model;

import java.util.Objects;

/**
 * 内容寻址哈希值（SHA-256，十六进制字符串）。
 *
 * @param value 64 位小写十六进制摘要
 */
public record ContentHash(String value) {
    public static final int HASH_LENGTH = 64;

    public ContentHash {
        Objects.requireNonNull(value, "hash value");
        if (value.length() != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "hash must be " + HASH_LENGTH + " hex chars, got " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!digit) {
                throw new IllegalArgumentException("hash contains illegal char: " + c);
            }
        }
    }

    public static ContentHash of(String hex) {
        return new ContentHash(hex);
    }

    @Override
    public String toString() {
        return value;
    }
}

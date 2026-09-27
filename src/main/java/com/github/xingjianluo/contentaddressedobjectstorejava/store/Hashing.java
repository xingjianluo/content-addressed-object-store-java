package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 摘要工具。 */
public final class Hashing {

    public static final String ALGORITHM = "SHA-256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static ContentHash sha256(byte[] content) {
        MessageDigest digest = newDigest();
        return new ContentHash(toHex(digest.digest(content)));
    }

    /** 流式计算摘要，同时返回内容长度，避免重复读盘。 */
    public static HashAndLength sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        long length = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            digest.update(buffer, 0, n);
            length += n;
        }
        return new HashAndLength(new ContentHash(toHex(digest.digest())), length);
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public record HashAndLength(ContentHash hash, long length) {
    }
}

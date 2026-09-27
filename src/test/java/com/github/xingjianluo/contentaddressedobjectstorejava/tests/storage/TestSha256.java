package com.github.xingjianluo.contentaddressedobjectstorejava.tests.storage;

import java.security.MessageDigest;

final class TestSha256 {
    private TestSha256() {
    }

    static String hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

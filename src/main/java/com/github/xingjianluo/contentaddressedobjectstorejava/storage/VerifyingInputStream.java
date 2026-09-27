package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streams blob bytes while computing SHA-256; when EOF is reached the digest is
 * compared against the content-addressed id and the length is checked.
 *
 * <p>A mismatch surfaces as {@link DigestMismatchIOException} so callers can
 * distinguish logical checksum failure from physical
 * {@link java.io.IOException}s.</p>
 */
class VerifyingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private final String expectedHex;
    private final long expectedSize;
    private long bytesRead;
    private boolean verified;

    VerifyingInputStream(InputStream in, String expectedHex, long expectedSize) {
        super(in);
        this.expectedHex = expectedHex;
        this.expectedSize = expectedSize;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            digest.update((byte) b);
            bytesRead++;
        } else {
            verifyIfEnd();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // Must delegate to the underlying stream directly (NOT super.read),
        // because FilterInputStream's default loop calls our read() and would
        // swallow its EOF marker (-1) into a 0-length return.
        int n = in.read(b, off, len);
        if (n > 0) {
            digest.update(b, off, n);
            bytesRead += n;
        } else if (n == -1) {
            verifyIfEnd();
        }
        return n;
    }

    private void verifyIfEnd() throws IOException {
        if (verified) {
            return;
        }
        verified = true;
        String actualHex = HexFormat.toHex(digest.digest());
        if (!expectedHex.equalsIgnoreCase(actualHex)) {
            throw new DigestMismatchIOException(expectedHex, actualHex);
        }
        if (expectedSize >= 0 && bytesRead != expectedSize) {
            throw new DigestMismatchIOException(
                    "blob length changed on disk: expected=" + expectedSize + ", actual=" + bytesRead);
        }
    }

    @Override
    public void close() throws IOException {
        // Verify at EOF even when the caller consumed exactly expectedSize bytes
        // without triggering an extra EOF probe. A deliberately short/partial
        // read does not verify (caller chose not to consume the whole blob).
        if (!verified && expectedSize >= 0 && bytesRead == expectedSize) {
            verifyIfEnd();
        }
        super.close();
    }
}

package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

import java.io.OutputStream;

/** Discards everything; used when hashing/verifying via transferTo. */
final class NullOutputStream extends OutputStream {
    static final NullOutputStream INSTANCE = new NullOutputStream();

    @Override
    public void write(int b) {
    }

    @Override
    public void write(byte[] b, int off, int len) {
    }
}

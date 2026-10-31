package com.techynature.nestasar.asar;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Random-access, read-only bytes. Backed by a FileChannel (works for java.io.File and Android file descriptors). */
public interface ByteSource extends Closeable {
    long size() throws IOException;

    /** Reads exactly {@code len} bytes starting at {@code position}. */
    void readFully(long position, byte[] buf, int off, int len) throws IOException;

    static ByteSource of(final FileChannel ch, final Closeable owner) {
        return new ByteSource() {
            @Override
            public long size() throws IOException {
                return ch.size();
            }

            @Override
            public void readFully(long position, byte[] buf, int off, int len) throws IOException {
                ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
                long p = position;
                while (bb.hasRemaining()) {
                    int n = ch.read(bb, p);
                    if (n < 0) throw new EOFException("Archive ended early (wanted byte " + p + ")");
                    p += n;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    ch.close();
                } finally {
                    if (owner != null) owner.close();
                }
            }
        };
    }
}

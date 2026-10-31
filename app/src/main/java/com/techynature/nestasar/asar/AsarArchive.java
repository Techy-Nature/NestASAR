package com.techynature.nestasar.asar;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An opened ASAR archive.
 *
 * Layout (all integers little-endian):
 * <pre>
 *  0  uint32  4                     (size of the "size pickle" payload)
 *  4  uint32  headerPickleSize      (bytes of the header pickle that follows)
 *  8  uint32  headerPayloadSize     (headerPickleSize - 4)
 * 12  int32   jsonLength
 * 16  bytes   JSON header, zero-padded to a multiple of 4
 *  8 + headerPickleSize: file data
 * </pre>
 */
public final class AsarArchive implements Closeable {
    /** Largest header we are willing to load (256 MiB is far beyond any real app). */
    private static final long MAX_HEADER = 256L * 1024 * 1024;

    private final ByteSource source;
    private final AsarEntry root;
    private final long dataOffset;
    private final int headerJsonLength;
    private final long archiveSize;

    private AsarArchive(ByteSource source, AsarEntry root, long dataOffset, int headerJsonLength, long archiveSize) {
        this.source = source;
        this.root = root;
        this.dataOffset = dataOffset;
        this.headerJsonLength = headerJsonLength;
        this.archiveSize = archiveSize;
    }

    public static AsarArchive open(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            return open(ByteSource.of(raf.getChannel(), raf));
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /** Parses the header. The archive takes ownership of {@code source} and closes it in {@link #close()}. */
    public static AsarArchive open(ByteSource source) throws IOException {
        long total = source.size();
        if (total < 16) throw new AsarException("File is too small to be an ASAR archive (" + total + " bytes).");

        byte[] head = new byte[16];
        source.readFully(0, head, 0, 16);
        long sizePickle = u32(head, 0);
        long headerPickleSize = u32(head, 4);
        long payloadSize = u32(head, 8);
        long jsonLen = s32(head, 12);

        if (sizePickle != 4) throw new AsarException("Not an ASAR archive (bad magic: first value is " + sizePickle + ", expected 4).");
        if (headerPickleSize < 8 || headerPickleSize > MAX_HEADER || 8 + headerPickleSize > total)
            throw new AsarException("Not an ASAR archive (header size " + headerPickleSize + " doesn't fit in a " + total + "-byte file).");
        if (payloadSize + 4 != headerPickleSize)
            throw new AsarException("Corrupt ASAR header (pickle sizes disagree: " + payloadSize + " vs " + headerPickleSize + ").");
        if (jsonLen < 2 || jsonLen > payloadSize - 4)
            throw new AsarException("Corrupt ASAR header (JSON length " + jsonLen + " is out of range).");

        byte[] json = new byte[(int) jsonLen];
        source.readFully(16, json, 0, json.length);
        Object parsed = Json.parse(new String(json, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new AsarException("Corrupt ASAR header (top level is not an object).");

        AsarEntry root = new AsarEntry("", AsarEntry.Kind.DIRECTORY, null);
        buildDir(root, asMap(parsed), 0);
        return new AsarArchive(source, root, 8 + headerPickleSize, (int) jsonLen, total);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw new AsarException("Corrupt ASAR header (expected an object).");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static void buildDir(AsarEntry dir, Map<String, Object> node, int depth) {
        if (depth > 512) throw new AsarException("Corrupt ASAR header (folders nested too deeply).");
        Object files = node.get("files");
        if (files == null) return;
        for (Map.Entry<String, Object> e : asMap(files).entrySet()) {
            String name = e.getKey();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
                throw new AsarException("Unsafe entry name in archive: \"" + name + "\"");
            Map<String, Object> m = asMap(e.getValue());
            AsarEntry child;
            if (m.containsKey("files")) {
                child = new AsarEntry(name, AsarEntry.Kind.DIRECTORY, dir);
                buildDir(child, m, depth + 1);
            } else if (m.containsKey("link")) {
                child = new AsarEntry(name, AsarEntry.Kind.LINK, dir);
                child.link = String.valueOf(m.get("link"));
            } else {
                child = new AsarEntry(name, AsarEntry.Kind.FILE, dir);
                child.size = toLong(m.get("size"), "size of " + child.path);
                child.unpacked = Boolean.TRUE.equals(m.get("unpacked"));
                child.executable = Boolean.TRUE.equals(m.get("executable"));
                if (!child.unpacked) child.offset = toLong(m.get("offset"), "offset of " + child.path);
                Object integ = m.get("integrity");
                if (integ instanceof Map) child.integrity = (Map<String, Object>) integ;
                if (child.size < 0 || child.offset < 0) throw new AsarException("Corrupt entry: " + child.path);
            }
            dir.children.put(name, child);
        }
    }

    private static long toLong(Object o, String what) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignored) { }
        }
        throw new AsarException("Corrupt ASAR header (missing or bad " + what + ").");
    }

    // ------------------------------------------------------------------ API

    public AsarEntry root() { return root; }

    /** Byte position where file data begins. */
    public long dataOffset() { return dataOffset; }

    public int headerJsonLength() { return headerJsonLength; }

    public long archiveSize() { return archiveSize; }

    /** Finds an entry by "a/b/c" path ("" = root). Returns null if missing. */
    public AsarEntry find(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return root;
        AsarEntry cur = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (!cur.isDirectory()) return null;
            cur = cur.child(part);
            if (cur == null) return null;
        }
        return cur;
    }

    /** All entries under {@code start} (depth-first, header order), not including start itself. */
    public List<AsarEntry> walk(AsarEntry start) {
        List<AsarEntry> out = new ArrayList<>();
        walk(start, out);
        return out;
    }

    private static void walk(AsarEntry e, List<AsarEntry> out) {
        for (AsarEntry c : e.children.values()) {
            out.add(c);
            if (c.isDirectory()) walk(c, out);
        }
    }

    /** Reads up to {@code max} bytes from the start of a packed file. */
    public byte[] readHead(AsarEntry file, int max) throws IOException {
        checkReadable(file);
        int n = (int) Math.min(max, file.size);
        byte[] b = new byte[n];
        source.readFully(dataOffset + file.offset, b, 0, n);
        return b;
    }

    /** A stream over a packed file's bytes. Not thread-safe across concurrent readers of the same archive. */
    public InputStream openStream(final AsarEntry file) {
        checkReadable(file);
        final long start = dataOffset + file.offset;
        final long end = start + file.size;
        return new InputStream() {
            long pos = start;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : (one[0] & 0xff);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (pos >= end) return -1;
                int n = (int) Math.min(len, end - pos);
                source.readFully(pos, b, off, n);
                pos += n;
                return n;
            }

            @Override
            public long skip(long n) {
                long k = Math.max(0, Math.min(n, end - pos));
                pos += k;
                return k;
            }

            @Override
            public int available() {
                return (int) Math.min(Integer.MAX_VALUE, end - pos);
            }
        };
    }

    private void checkReadable(AsarEntry file) {
        if (!file.isFile()) throw new AsarException(file.path + " is not a file.");
        if (file.unpacked) throw new AsarException(file.path + " is stored outside the archive (in the .asar.unpacked folder).");
        if (dataOffset + file.offset + file.size > archiveSize)
            throw new AsarException(file.path + " points past the end of the archive (the file may be truncated).");
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    // ------------------------------------------------------------- helpers

    static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    static int s32(byte[] b, int o) {
        return (int) u32(b, o);
    }
}

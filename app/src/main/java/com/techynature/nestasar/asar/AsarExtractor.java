package com.techynature.nestasar.asar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Copies entries out of an {@link AsarArchive} into any {@link Sink} (a real folder, Android SAF, ...). */
public final class AsarExtractor {

    /** Where extracted things go. Paths are relative ("a/b/c"), parents are always created first. */
    public interface Sink {
        void makeDir(String path) throws IOException;

        OutputStream createFile(String path) throws IOException;
    }

    public static final class Result {
        public int files;
        public int folders;
        public long bytes;
        /** Entries that could not be written, with the reason. */
        public final List<String> skipped = new ArrayList<>();
        /** Files whose SHA256 did not match the header's integrity hash. */
        public final List<String> integrityFailures = new ArrayList<>();
    }

    private AsarExtractor() { }

    /**
     * Extracts {@code start} (a folder or a single file) into {@code sink}.
     * Paths written to the sink are relative to {@code start}'s parent, so
     * extracting folder "resources" produces "resources/..." and a single
     * file produces just its name. Extracting the root writes its contents directly.
     */
    public static Result extract(AsarArchive archive, AsarEntry start, Sink sink, boolean verify, Progress progress) throws IOException {
        Result r = new Result();
        long total = start.totalSize();
        long[] done = {0};
        String base = start.parent == null ? "" : start.parent.path;

        if (start.isDirectory()) {
            if (start.parent != null) {
                sink.makeDir(rel(base, start));
                r.folders++;
            }
            for (AsarEntry e : archive.walk(start)) {
                if (progress.isCancelled()) throw new AsarException("Cancelled.");
                one(archive, e, rel(base, e), sink, verify, progress, r, done, total);
            }
        } else {
            one(archive, start, rel(base, start), sink, verify, progress, r, done, total);
        }
        progress.update("Done", "", total, total);
        return r;
    }

    private static String rel(String base, AsarEntry e) {
        if (base.isEmpty()) return e.path;
        return e.path.substring(base.length() + 1);
    }

    private static void one(AsarArchive archive, AsarEntry e, String out, Sink sink, boolean verify,
                            Progress progress, Result r, long[] done, long total) throws IOException {
        switch (e.kind) {
            case DIRECTORY:
                sink.makeDir(out);
                r.folders++;
                return;
            case LINK:
                r.skipped.add(e.path + "  (symlink to " + e.link + " — Android storage can't hold symlinks)");
                return;
            case FILE:
            default:
                break;
        }
        if (e.unpacked) {
            r.skipped.add(e.path + "  (stored in the .asar.unpacked folder, not inside the archive)");
            done[0] += e.size;
            return;
        }
        if (archive.dataOffset() + e.offset + e.size > archive.archiveSize()) {
            r.skipped.add(e.path + "  (data is past the end of the archive — truncated file?)");
            done[0] += e.size;
            return;
        }

        MessageDigest md = null;
        String expected = verify ? e.integrityHash() : null;
        if (expected != null) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ignored) { }
        }

        byte[] buf = new byte[256 * 1024];
        try (InputStream in = archive.openStream(e); OutputStream os = sink.createFile(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (progress.isCancelled()) throw new AsarException("Cancelled.");
                os.write(buf, 0, n);
                if (md != null) md.update(buf, 0, n);
                done[0] += n;
                r.bytes += n;
                progress.update("Extracting", e.path, done[0], total);
            }
        }
        r.files++;
        if (md != null && !Hex.of(md.digest()).equalsIgnoreCase(expected)) r.integrityFailures.add(e.path);
    }
}

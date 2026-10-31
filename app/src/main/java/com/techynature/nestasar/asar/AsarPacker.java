package com.techynature.nestasar.asar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an ASAR archive from a folder tree, compatible with Electron and @electron/asar.
 *
 * Two passes: the first reads every file to learn its exact size and SHA256 block hashes
 * (Electron's "integrity" field), the second writes the header and then the file bytes.
 */
public final class AsarPacker {

    /** A folder or file to pack. Implementations exist for java.io.File and Android SAF. */
    public interface Node {
        String name();

        boolean isDirectory();

        /** Children of a folder (any order — the packer sorts them). */
        List<? extends Node> children() throws IOException;

        InputStream open() throws IOException;
    }

    public static final class Options {
        /** Write SHA256 "integrity" data (needed by Electron apps that enable ASAR integrity checks). */
        public boolean integrity = true;
        /** Skip dot-files and dot-folders such as .git or .DS_Store. */
        public boolean skipHidden = false;
    }

    public static final class Result {
        public int files;
        public int folders;
        public long bytes;
        public long archiveSize;
    }

    static final int BLOCK_SIZE = 4 * 1024 * 1024;
    /** Electron stores sizes as JS numbers and refuses files over ~4 GiB. */
    static final long MAX_FILE = 0xFFFFFFFFL;

    private AsarPacker() { }

    private static final class Planned {
        final Node node;
        final String path;
        final long size;

        Planned(Node node, String path, long size) {
            this.node = node;
            this.path = path;
            this.size = size;
        }
    }

    public static Result pack(Node rootFolder, OutputStream out, Options opt, Progress progress) throws IOException {
        if (!rootFolder.isDirectory()) throw new AsarException("Pick a folder to pack, not a file.");
        Result r = new Result();
        List<Planned> plan = new ArrayList<>();
        long[] offset = {0};

        // ---- pass 1: header + hashes
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("files", dir(rootFolder, "", opt, progress, plan, offset, r, 0));

        byte[] json = Json.write(header).getBytes(StandardCharsets.UTF_8);
        int pad = (4 - (json.length % 4)) % 4;
        long payload = 4L + json.length + pad;
        long headerPickle = 4L + payload;

        byte[] head = new byte[16];
        putU32(head, 0, 4);
        putU32(head, 4, headerPickle);
        putU32(head, 8, payload);
        putU32(head, 12, json.length);
        out.write(head);
        out.write(json);
        if (pad > 0) out.write(new byte[pad]);
        long written = 16L + json.length + pad;

        // ---- pass 2: data
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        long total = offset[0];
        for (Planned p : plan) {
            long n = 0;
            try (InputStream in = p.node.open()) {
                int k;
                while ((k = in.read(buf)) > 0) {
                    if (progress.isCancelled()) throw new AsarException("Cancelled.");
                    if (n + k > p.size) throw changed(p.path);
                    out.write(buf, 0, k);
                    n += k;
                    done += k;
                    progress.update("Writing", p.path, done, total);
                }
            }
            if (n != p.size) throw changed(p.path);
            written += n;
        }
        out.flush();
        r.bytes = total;
        r.archiveSize = written;
        progress.update("Done", "", total, total);
        return r;
    }

    private static AsarException changed(String path) {
        return new AsarException(path + " changed while packing. Close anything editing that folder and try again.");
    }

    private static Map<String, Object> dir(Node folder, String path, Options opt, Progress progress,
                                           List<Planned> plan, long[] offset, Result r, int depth) throws IOException {
        if (depth > 256) throw new AsarException("Folders are nested too deeply at " + path);
        List<Node> kids = new ArrayList<>(folder.children());
        Collections.sort(kids, (a, b) -> a.name().compareTo(b.name()));

        Map<String, Object> files = new LinkedHashMap<>();
        for (Node k : kids) {
            if (progress.isCancelled()) throw new AsarException("Cancelled.");
            String name = k.name();
            if (name == null || name.isEmpty() || name.contains("/")) continue;
            if (opt.skipHidden && name.startsWith(".")) continue;
            String childPath = path.isEmpty() ? name : path + "/" + name;
            Map<String, Object> entry = new LinkedHashMap<>();
            if (k.isDirectory()) {
                entry.put("files", dir(k, childPath, opt, progress, plan, offset, r, depth + 1));
                r.folders++;
            } else {
                Hashed h = hash(k, childPath, opt.integrity, progress);
                if (h.size > MAX_FILE) throw new AsarException(childPath + " is larger than 4 GB, which ASAR can't store.");
                entry.put("size", h.size);
                entry.put("offset", Long.toString(offset[0]));
                if (h.integrity != null) entry.put("integrity", h.integrity);
                plan.add(new Planned(k, childPath, h.size));
                offset[0] += h.size;
                r.files++;
            }
            files.put(name, entry);
        }
        return files;
    }

    private static final class Hashed {
        long size;
        Map<String, Object> integrity;
    }

    /** Matches @electron/asar's getFileIntegrity(): whole-file hash plus one hash per 4 MiB block (always at least one, the last may be empty). */
    private static Hashed hash(Node file, String path, boolean integrity, Progress progress) throws IOException {
        Hashed h = new Hashed();
        MessageDigest whole = null, block = null;
        List<Object> blocks = null;
        if (integrity) {
            try {
                whole = MessageDigest.getInstance("SHA-256");
                block = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AsarException("SHA-256 is not available on this device.", e);
            }
            blocks = new ArrayList<>();
        }
        byte[] buf = new byte[256 * 1024];
        long inBlock = 0;
        try (InputStream in = file.open()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (progress.isCancelled()) throw new AsarException("Cancelled.");
                h.size += n;
                if (integrity) {
                    whole.update(buf, 0, n);
                    int off = 0;
                    while (off < n) {
                        int take = (int) Math.min(BLOCK_SIZE - inBlock, n - off);
                        block.update(buf, off, take);
                        inBlock += take;
                        off += take;
                        if (inBlock == BLOCK_SIZE) {
                            blocks.add(Hex.of(block.digest()));
                            inBlock = 0;
                        }
                    }
                }
                progress.update("Reading", path, h.size, 0);
            }
        }
        if (integrity) {
            blocks.add(Hex.of(block.digest()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithm", "SHA256");
            m.put("hash", Hex.of(whole.digest()));
            m.put("blockSize", (long) BLOCK_SIZE);
            m.put("blocks", blocks);
            h.integrity = m;
        }
        return h;
    }

    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
}

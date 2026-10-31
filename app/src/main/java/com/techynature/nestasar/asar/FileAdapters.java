package com.techynature.nestasar.asar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** java.io.File versions of the packer input and extractor output. */
public final class FileAdapters {
    private FileAdapters() { }

    public static AsarPacker.Node node(final File f) {
        return new AsarPacker.Node() {
            @Override public String name() { return f.getName(); }

            @Override public boolean isDirectory() { return f.isDirectory(); }

            @Override
            public List<AsarPacker.Node> children() throws IOException {
                File[] list = f.listFiles();
                if (list == null) throw new IOException("Can't list " + f);
                List<AsarPacker.Node> out = new ArrayList<>();
                for (File c : list) out.add(node(c));
                return out;
            }

            @Override
            public InputStream open() throws IOException {
                return new FileInputStream(f);
            }
        };
    }

    public static AsarExtractor.Sink sink(final File dir) {
        return new AsarExtractor.Sink() {
            @Override
            public void makeDir(String path) throws IOException {
                File d = safe(dir, path);
                if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Can't create folder " + d);
            }

            @Override
            public OutputStream createFile(String path) throws IOException {
                File out = safe(dir, path);
                File p = out.getParentFile();
                if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("Can't create folder " + p);
                return new FileOutputStream(out);
            }
        };
    }

    /** Refuses paths that would escape the destination folder. */
    static File safe(File base, String rel) throws IOException {
        File f = new File(base, rel);
        String b = base.getCanonicalPath();
        String c = f.getCanonicalPath();
        if (!c.equals(b) && !c.startsWith(b + File.separator)) throw new IOException("Refusing to write outside the destination: " + rel);
        return f;
    }
}

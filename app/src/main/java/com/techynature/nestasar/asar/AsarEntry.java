package com.techynature.nestasar.asar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One node of an ASAR header tree: a directory, a file, or a symlink. */
public final class AsarEntry {
    public enum Kind { DIRECTORY, FILE, LINK }

    public final String name;
    public final String path; // "" for root, otherwise "a/b/c"
    public final Kind kind;
    public final AsarEntry parent;

    // files
    public long size;
    public long offset;          // relative to the start of the data section
    public boolean unpacked;     // data lives in <archive>.asar.unpacked/
    public boolean executable;
    public Map<String, Object> integrity; // raw integrity object, may be null

    // links
    public String link;

    // directories (insertion order = header order)
    final LinkedHashMap<String, AsarEntry> children = new LinkedHashMap<>();

    AsarEntry(String name, Kind kind, AsarEntry parent) {
        this.name = name;
        this.kind = kind;
        this.parent = parent;
        if (parent == null || parent.path.isEmpty()) this.path = name;
        else this.path = parent.path + "/" + name;
    }

    public boolean isDirectory() { return kind == Kind.DIRECTORY; }
    public boolean isFile() { return kind == Kind.FILE; }
    public boolean isLink() { return kind == Kind.LINK; }

    public AsarEntry child(String name) { return children.get(name); }

    /** Children sorted: folders first, then by name (case-insensitive). */
    public List<AsarEntry> sortedChildren() {
        List<AsarEntry> list = new ArrayList<>(children.values());
        Collections.sort(list, (a, b) -> {
            boolean ad = a.isDirectory(), bd = b.isDirectory();
            if (ad != bd) return ad ? -1 : 1;
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.name.compareTo(b.name);
        });
        return list;
    }

    /** Header-order children (the order they appear in the archive). */
    public List<AsarEntry> children() { return new ArrayList<>(children.values()); }

    /** Total size of all packed + unpacked file bytes under this node. */
    public long totalSize() {
        if (isFile()) return size;
        long t = 0;
        for (AsarEntry c : children.values()) t += c.totalSize();
        return t;
    }

    /** Number of files (not folders or links) under this node, counting itself if it is a file. */
    public int fileCount() {
        if (isFile()) return 1;
        int n = 0;
        for (AsarEntry c : children.values()) n += c.fileCount();
        return n;
    }

    public int folderCount() {
        if (!isDirectory()) return 0;
        int n = 0;
        for (AsarEntry c : children.values()) if (c.isDirectory()) n += 1 + c.folderCount();
        return n;
    }

    /** SHA256 hex hash stored in the header, or null. */
    public String integrityHash() {
        if (integrity == null) return null;
        Object h = integrity.get("hash");
        return h instanceof String ? (String) h : null;
    }

    @Override
    public String toString() {
        return path.isEmpty() ? "/" : path;
    }
}

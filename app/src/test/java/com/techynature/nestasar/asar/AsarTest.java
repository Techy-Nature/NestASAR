package com.techynature.nestasar.asar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class AsarTest {
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static File tmpDir(String name) throws IOException {
        return Files.createTempDirectory("nestasar-" + name).toFile();
    }

    private static void write(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    @Test
    public void packOpenExtractRoundTrip() throws Exception {
        File src = tmpDir("src");
        write(new File(src, "a.txt"), "hello\n".getBytes(StandardCharsets.UTF_8));
        write(new File(src, "empty.bin"), new byte[0]);
        write(new File(src, "sub/four-mib.bin"), random(AsarPacker.BLOCK_SIZE, 1));
        write(new File(src, "sub/big.bin"), random(AsarPacker.BLOCK_SIZE * 2 + 123, 2));
        write(new File(src, "sub/deep/z.js"), "x\n".getBytes(StandardCharsets.UTF_8));
        write(new File(src, "ünïcødé \"quoted\".txt"), "u".getBytes(StandardCharsets.UTF_8));
        new File(src, "emptydir").mkdirs();

        File asar = new File(tmpDir("out"), "app.asar");
        AsarPacker.Result pr;
        try (FileOutputStream o = new FileOutputStream(asar)) {
            pr = AsarPacker.pack(FileAdapters.node(src), o, new AsarPacker.Options(), Progress.NONE);
        }
        assertEquals(6, pr.files);
        assertEquals(asar.length(), pr.archiveSize);

        try (AsarArchive a = AsarArchive.open(asar)) {
            assertEquals(6, a.root().fileCount());
            AsarEntry four = a.find("sub/four-mib.bin");
            assertNotNull(four);
            List<?> blocks = (List<?>) four.integrity.get("blocks");
            assertEquals(2, blocks.size());
            assertEquals(EMPTY_SHA, blocks.get(1));

            AsarEntry empty = a.find("empty.bin");
            assertEquals(0, empty.size);
            assertEquals(EMPTY_SHA, empty.integrityHash());

            AsarEntry big = a.find("sub/big.bin");
            assertEquals(3, ((List<?>) big.integrity.get("blocks")).size());
            assertArrayEquals(random(AsarPacker.BLOCK_SIZE * 2 + 123, 2), readAll(a.openStream(big)));
            assertTrue(a.find("emptydir").isDirectory());
            assertNotNull(a.find("ünïcødé \"quoted\".txt"));

            File dest = tmpDir("dest");
            AsarExtractor.Result er = AsarExtractor.extract(a, a.root(), FileAdapters.sink(dest), true, Progress.NONE);
            assertEquals(6, er.files);
            assertTrue(er.integrityFailures.isEmpty());
            assertTrue(er.skipped.isEmpty());
            assertArrayEquals(Files.readAllBytes(new File(src, "sub/big.bin").toPath()),
                    Files.readAllBytes(new File(dest, "sub/big.bin").toPath()));
            assertEquals("hello\n", new String(Files.readAllBytes(new File(dest, "a.txt").toPath()), StandardCharsets.UTF_8));
            assertTrue(new File(dest, "emptydir").isDirectory());

            // single folder extraction keeps the folder name
            File one = tmpDir("one");
            AsarExtractor.extract(a, a.find("sub/deep"), FileAdapters.sink(one), true, Progress.NONE);
            assertEquals("x\n", new String(Files.readAllBytes(new File(one, "deep/z.js").toPath()), StandardCharsets.UTF_8));

            // single file extraction is just the file
            File single = tmpDir("single");
            AsarExtractor.extract(a, a.find("sub/deep/z.js"), FileAdapters.sink(single), true, Progress.NONE);
            assertTrue(new File(single, "z.js").isFile());
        }

        // repacking the extracted tree gives a byte-identical archive
        File dest2 = tmpDir("dest2");
        try (AsarArchive a = AsarArchive.open(asar)) {
            AsarExtractor.extract(a, a.root(), FileAdapters.sink(dest2), true, Progress.NONE);
        }
        File asar2 = new File(tmpDir("out2"), "app.asar");
        try (FileOutputStream o = new FileOutputStream(asar2)) {
            AsarPacker.pack(FileAdapters.node(dest2), o, new AsarPacker.Options(), Progress.NONE);
        }
        assertArrayEquals(Files.readAllBytes(asar.toPath()), Files.readAllBytes(asar2.toPath()));
    }

    /** An archive laid out exactly as @electron/asar writes one, built by hand. */
    @Test
    public void readsHandBuiltArchive() throws Exception {
        String json = "{\"files\":{\"a.txt\":{\"size\":6,\"offset\":\"0\"},\"d\":{\"files\":{\"b\":{\"size\":2,\"offset\":\"6\",\"executable\":true},"
                + "\"l\":{\"link\":\"d/b\"},\"u.node\":{\"size\":9,\"unpacked\":true}}}}}";
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int pad = (4 - j.length % 4) % 4;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int payload = 4 + j.length + pad;
        writeU32(o, 4);
        writeU32(o, payload + 4);
        writeU32(o, payload);
        writeU32(o, j.length);
        o.write(j);
        o.write(new byte[pad]);
        o.write("hello\nhi".getBytes(StandardCharsets.UTF_8));
        File f = new File(tmpDir("hand"), "x.asar");
        write(f, o.toByteArray());

        try (AsarArchive a = AsarArchive.open(f)) {
            assertEquals("hello\n", new String(readAll(a.openStream(a.find("a.txt"))), StandardCharsets.UTF_8));
            assertEquals("hi", new String(readAll(a.openStream(a.find("d/b"))), StandardCharsets.UTF_8));
            assertTrue(a.find("d/b").executable);
            assertTrue(a.find("d/l").isLink());
            assertTrue(a.find("d/u.node").unpacked);
            File dest = tmpDir("handdest");
            AsarExtractor.Result r = AsarExtractor.extract(a, a.root(), FileAdapters.sink(dest), true, Progress.NONE);
            assertEquals(2, r.files);
            assertEquals(2, r.skipped.size());
        }
    }

    @Test
    public void rejectsGarbageAndUnsafeNames() throws Exception {
        File junk = new File(tmpDir("junk"), "junk.asar");
        write(junk, random(1000, 3));
        try {
            AsarArchive.open(junk).close();
            fail("garbage accepted");
        } catch (AsarException expected) {
            // ok
        }

        String json = "{\"files\":{\"..\":{\"size\":1,\"offset\":\"0\"}}}";
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int pad = (4 - j.length % 4) % 4;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU32(o, 4);
        writeU32(o, 8 + j.length + pad);
        writeU32(o, 4 + j.length + pad);
        writeU32(o, j.length);
        o.write(j);
        o.write(new byte[pad]);
        o.write('x');
        File bad = new File(tmpDir("bad"), "bad.asar");
        write(bad, o.toByteArray());
        try {
            AsarArchive.open(bad).close();
            fail("unsafe name accepted");
        } catch (AsarException expected) {
            // ok
        }
    }

    @Test
    public void jsonRoundTrip() {
        Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\"],\"b\":{\"c\":true,\"d\":null}}");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(1L, ((List<?>) m.get("a")).get(0));
        assertEquals("x\né", ((List<?>) m.get("a")).get(2));
        assertEquals("{\"a\":[1,2.5,\"x\\né\"],\"b\":{\"c\":true,\"d\":null}}", Json.write(v));
    }

    private static void writeU32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xff));
        o.write((int) ((v >> 8) & 0xff));
        o.write((int) ((v >> 16) & 0xff));
        o.write((int) ((v >> 24) & 0xff));
    }
}

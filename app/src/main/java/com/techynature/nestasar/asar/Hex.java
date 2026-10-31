package com.techynature.nestasar.asar;

/** Lowercase hex encoding. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() { }

    public static String of(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = DIGITS[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = DIGITS[b[i] & 0xf];
        }
        return new String(c);
    }
}

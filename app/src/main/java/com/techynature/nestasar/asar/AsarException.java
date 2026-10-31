package com.techynature.nestasar.asar;

/** Thrown for anything wrong with an ASAR archive or a pack/extract job. */
public class AsarException extends RuntimeException {
    public AsarException(String message) {
        super(message);
    }

    public AsarException(String message, Throwable cause) {
        super(message, cause);
    }
}

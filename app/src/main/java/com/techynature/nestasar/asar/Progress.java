package com.techynature.nestasar.asar;

/** Progress callback for long jobs. Return false from {@link #isCancelled()} to keep going. */
public interface Progress {
    /** @param stage short label, e.g. "Hashing" or "Writing"
     *  @param path  entry currently being worked on
     *  @param done  bytes processed so far in this stage
     *  @param total bytes expected in this stage (0 if unknown) */
    void update(String stage, String path, long done, long total);

    boolean isCancelled();

    Progress NONE = new Progress() {
        @Override public void update(String stage, String path, long done, long total) { }
        @Override public boolean isCancelled() { return false; }
    };
}

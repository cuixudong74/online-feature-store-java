package com.github.cuixudong74.onlinefeaturestorejava.materialization;

/**
 * Applies the feature log to the online store in offset order, committing a
 * checkpoint only after each batch is fully applied. On restart it resumes
 * from the last committed checkpoint: entries already applied are skipped
 * (never re-applied), entries not yet applied are applied (never skipped).
 */
public interface Materializer {
    /** Apply all currently available log entries. Returns the committed offset. */
    long materializeOnce();

    /** Current committed offset for this materializer. */
    long committedOffset();
}

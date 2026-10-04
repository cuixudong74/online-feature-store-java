package com.github.cuixudong74.onlinefeaturestorejava.store;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;

import java.util.List;

/**
 * Append-only feature write log. Each appended batch is assigned a monotonically
 * increasing offset; offsets are the unit of commit and recovery.
 */
public interface FeatureLog {
    /** Append a batch, returning the offset of the first entry. */
    long append(List<FeatureValue> batch);

    /** Read up to maxEntries entries starting at offset (inclusive). */
    List<LogEntry> readFrom(long offset, int maxEntries);

    /** The next offset that will be assigned (i.e. total entries appended). */
    long nextOffset();

    record LogEntry(long offset, FeatureValue value) {}
}

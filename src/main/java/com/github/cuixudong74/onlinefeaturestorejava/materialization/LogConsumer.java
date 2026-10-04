package com.github.cuixudong74.onlinefeaturestorejava.materialization;

import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog.LogEntry;

import java.util.List;

/**
 * An independent consumer of the feature log with its own checkpoint.
 * Consumers do not block each other or the write path: each pulls from the
 * shared log at its own pace.
 */
public interface LogConsumer {
    String id();

    /** Process up to maxEntries pending entries; returns entries processed. */
    int poll(int maxEntries);

    /** This consumer's committed offset. */
    long committedOffset();

    /** Callback invoked with a batch of entries in offset order. */
    interface Handler {
        void onBatch(List<LogEntry> batch);
    }
}

package com.github.cuixudong74.onlinefeaturestorejava.store;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only in-memory log. Appends are atomic and assign contiguous offsets;
 * readers at any offset always see a consistent prefix of the log.
 */
public class InMemoryFeatureLog implements FeatureLog {
    private final CopyOnWriteArrayList<FeatureValue> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong nextOffset = new AtomicLong(0);

    @Override
    public long append(List<FeatureValue> batch) {
        if (batch.isEmpty()) return nextOffset.get();
        long base;
        synchronized (this) {
            base = nextOffset.get();
            entries.addAll(batch);
            nextOffset.set(base + batch.size());
        }
        return base;
    }

    @Override
    public List<LogEntry> readFrom(long offset, int maxEntries) {
        List<LogEntry> result = new ArrayList<>();
        long end = Math.min(nextOffset.get(), offset + maxEntries);
        for (long i = Math.max(0, offset); i < end; i++) {
            result.add(new LogEntry(i, entries.get((int) i)));
        }
        return result;
    }

    @Override
    public long nextOffset() {
        return nextOffset.get();
    }
}

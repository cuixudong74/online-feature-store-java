package com.github.cuixudong74.onlinefeaturestorejava.materialization;

import com.github.cuixudong74.onlinefeaturestorejava.store.CheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog.LogEntry;

import java.util.List;
import java.util.Objects;

/**
 * Independent pull-based consumer. Each consumer keeps its own checkpoint in
 * the shared CheckpointStore and reads directly from the shared log, so a slow
 * consumer neither blocks faster consumers nor holds up appends to the log.
 */
public class DefaultLogConsumer implements LogConsumer {
    private final String id;
    private final FeatureLog log;
    private final CheckpointStore checkpoints;
    private final Handler handler;
    private long committed;

    public DefaultLogConsumer(String id, FeatureLog log, CheckpointStore checkpoints, Handler handler) {
        this.id = Objects.requireNonNull(id);
        this.log = log;
        this.checkpoints = checkpoints;
        this.handler = handler;
        this.committed = checkpoints.load(id).orElse(0L);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized int poll(int maxEntries) {
        List<LogEntry> batch = log.readFrom(committed, maxEntries);
        if (batch.isEmpty()) return 0;
        handler.onBatch(batch);
        committed = batch.get(batch.size() - 1).offset() + 1;
        checkpoints.commit(id, committed);
        return batch.size();
    }

    @Override
    public long committedOffset() {
        return committed;
    }
}

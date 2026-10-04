package com.github.cuixudong74.onlinefeaturestorejava.materialization;

import com.github.cuixudong74.onlinefeaturestorejava.store.CheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog.LogEntry;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureStore;

import java.util.List;

/**
 * Recovery semantics:
 * <ul>
 *   <li>On construction, resumes from the last committed checkpoint (0 if none).</li>
 *   <li>Each batch is applied to the store first, then the checkpoint is committed.
 *       A crash between apply and commit causes the batch to be re-applied after
 *       restart — safe because store writes are idempotent (deterministic
 *       (eventTime, seq, sourceId) resolution), so nothing is applied twice in
 *       effect and nothing is skipped.</li>
 *   <li>The checkpoint only ever reflects fully applied batches, so committed
 *       and in-flight state are always distinguishable.</li>
 * </ul>
 */
public class DefaultMaterializer implements Materializer {
    public static final String CONSUMER_ID = "materializer";

    private final FeatureLog log;
    private final FeatureStore store;
    private final CheckpointStore checkpoints;
    private final int batchSize;
    private long committed;

    public DefaultMaterializer(FeatureLog log, FeatureStore store, CheckpointStore checkpoints, int batchSize) {
        this.log = log;
        this.store = store;
        this.checkpoints = checkpoints;
        this.batchSize = batchSize;
        this.committed = checkpoints.load(CONSUMER_ID).orElse(0L);
    }

    @Override
    public synchronized long materializeOnce() {
        while (true) {
            List<LogEntry> batch = log.readFrom(committed, batchSize);
            if (batch.isEmpty()) break;
            store.write(batch.stream().map(LogEntry::value).toList());
            committed = batch.get(batch.size() - 1).offset() + 1;
            checkpoints.commit(CONSUMER_ID, committed);
        }
        return committed;
    }

    @Override
    public long committedOffset() {
        return committed;
    }
}

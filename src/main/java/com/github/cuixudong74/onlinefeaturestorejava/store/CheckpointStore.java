package com.github.cuixudong74.onlinefeaturestorejava.store;

import java.util.Map;
import java.util.Optional;

/**
 * Durable per-consumer checkpoints. A checkpoint is only written after the
 * corresponding log entries have been fully applied to the feature store,
 * so recovery can resume exactly at the last committed position.
 */
public interface CheckpointStore {
    void commit(String consumerId, long offset);

    Optional<Long> load(String consumerId);

    Map<String, Long> loadAll();
}

package com.github.cuixudong74.onlinefeaturestorejava.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory checkpoint store. Checkpoints are monotonically increasing per
 * consumer: committing an older offset than the stored one is ignored, which
 * keeps committed state distinguishable from in-flight (uncommitted) progress.
 */
public class InMemoryCheckpointStore implements CheckpointStore {
    private final Map<String, Long> checkpoints = new ConcurrentHashMap<>();

    @Override
    public void commit(String consumerId, long offset) {
        checkpoints.merge(consumerId, offset, Math::max);
    }

    @Override
    public Optional<Long> load(String consumerId) {
        return Optional.ofNullable(checkpoints.get(consumerId));
    }

    @Override
    public Map<String, Long> loadAll() {
        return Map.copyOf(checkpoints);
    }
}

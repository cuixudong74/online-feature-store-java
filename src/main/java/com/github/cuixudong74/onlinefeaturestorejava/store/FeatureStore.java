package com.github.cuixudong74.onlinefeaturestorejava.store;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Versioned, immutable-snapshot feature storage.
 *
 * <p>Writes are applied atomically per (entity, feature, version) timeline and
 * resolved deterministically so out-of-order and duplicate writes converge.
 * Reads never observe a partially applied write.
 */
public interface FeatureStore {
    /** Apply a batch of writes atomically. */
    void write(List<FeatureValue> values);

    /**
     * Latest value with eventTime &le; asOf, never anything after asOf.
     * The returned value is a complete immutable snapshot.
     */
    Optional<FeatureValue> readAsOf(String entityId, String featureName, int version, Instant asOf);

    /**
     * The value immediately before the given one on the same timeline
     * (used for staleness fallback). Empty if none.
     */
    Optional<FeatureValue> readPrevious(String entityId, String featureName, int version, FeatureValue value);
}

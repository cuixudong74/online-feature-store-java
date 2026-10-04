package com.github.cuixudong74.onlinefeaturestorejava.store;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory versioned store.
 *
 * <p>Consistency model:
 * <ul>
 *   <li>Each (entity, feature, version) has a timeline keyed by eventTime.</li>
 *   <li>A write batch is applied under a single write lock, so readers always
 *       observe either the state before the batch or after the whole batch —
 *       never a half-applied batch. Values are immutable, so no field-level mixing.</li>
 *   <li>Duplicate writes at the same eventTime are resolved deterministically by
 *       (seq, sourceId); applying the same writes in any order converges to the
 *       same state, and re-applying a write is a no-op.</li>
 *   <li>Reads use floorEntry(asOf), so a value with eventTime &gt; asOf can never
 *       be returned (no feature leakage from the future).</li>
 * </ul>
 */
public class InMemoryFeatureStore implements FeatureStore {
    private record Key(String entityId, String featureName, int version) {}

    private final Map<Key, ConcurrentSkipListMap<Instant, FeatureValue>> timelines = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void write(List<FeatureValue> values) {
        lock.writeLock().lock();
        try {
            for (FeatureValue v : values) {
                Key key = new Key(v.getEntityId(), v.getFeatureName(), v.getFeatureVersion());
                ConcurrentSkipListMap<Instant, FeatureValue> timeline =
                        timelines.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
                timeline.merge(v.getEventTime(), v, (existing, incoming) ->
                        incoming.supersedes(existing) ? incoming : existing);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<FeatureValue> readAsOf(String entityId, String featureName, int version, Instant asOf) {
        lock.readLock().lock();
        try {
            ConcurrentSkipListMap<Instant, FeatureValue> timeline =
                    timelines.get(new Key(entityId, featureName, version));
            if (timeline == null) return Optional.empty();
            Map.Entry<Instant, FeatureValue> entry = timeline.floorEntry(asOf);
            return Optional.ofNullable(entry).map(Map.Entry::getValue);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<FeatureValue> readPrevious(String entityId, String featureName, int version, FeatureValue value) {
        lock.readLock().lock();
        try {
            ConcurrentSkipListMap<Instant, FeatureValue> timeline =
                    timelines.get(new Key(entityId, featureName, version));
            if (timeline == null) return Optional.empty();
            Map.Entry<Instant, FeatureValue> entry = timeline.lowerEntry(value.getEventTime());
            return Optional.ofNullable(entry).map(Map.Entry::getValue);
        } finally {
            lock.readLock().unlock();
        }
    }
}

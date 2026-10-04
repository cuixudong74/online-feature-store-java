package com.github.cuixudong74.onlinefeaturestorejava.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A single immutable feature value at a point in time.
 *
 * <p>Ordering: two writes to the same (entity, feature, eventTime) are resolved
 * deterministically by (eventTime, seq, sourceId) so that any interleaving of
 * the same set of writes converges to the same state (idempotent, order-insensitive).
 */
public final class FeatureValue {
    private final String entityId;
    private final String featureName;
    private final int featureVersion;
    private final Instant eventTime;
    private final Map<String, Object> values;
    private final long seq;
    private final String sourceId;

    public FeatureValue(String entityId, String featureName, int featureVersion,
                        Instant eventTime, Map<String, Object> values, long seq, String sourceId) {
        this.entityId = Objects.requireNonNull(entityId);
        this.featureName = Objects.requireNonNull(featureName);
        this.featureVersion = featureVersion;
        this.eventTime = Objects.requireNonNull(eventTime);
        this.values = Map.copyOf(values);
        this.seq = seq;
        this.sourceId = sourceId == null ? "" : sourceId;
    }

    public String getEntityId() { return entityId; }
    public String getFeatureName() { return featureName; }
    public int getFeatureVersion() { return featureVersion; }
    public Instant getEventTime() { return eventTime; }
    public Map<String, Object> getValues() { return values; }
    public long getSeq() { return seq; }
    public String getSourceId() { return sourceId; }

    /** Deterministic tie-break for duplicate writes at the same eventTime. */
    public boolean supersedes(FeatureValue other) {
        if (this.seq != other.seq) return this.seq > other.seq;
        return this.sourceId.compareTo(other.sourceId) > 0;
    }
}

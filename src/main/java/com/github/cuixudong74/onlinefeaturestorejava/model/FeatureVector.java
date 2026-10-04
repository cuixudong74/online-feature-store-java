package com.github.cuixudong74.onlinefeaturestorejava.model;

import java.time.Instant;
import java.util.List;

/**
 * Result of a point-in-time read for one entity.
 */
public final class FeatureVector {
    private final String entityId;
    private final Instant asOf;
    private final List<FeatureRow> rows;

    public FeatureVector(String entityId, Instant asOf, List<FeatureRow> rows) {
        this.entityId = entityId;
        this.asOf = asOf;
        this.rows = List.copyOf(rows);
    }

    public String getEntityId() { return entityId; }
    public Instant getAsOf() { return asOf; }
    public List<FeatureRow> getRows() { return rows; }
}

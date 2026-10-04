package com.github.cuixudong74.onlinefeaturestorejava.model;

import java.time.Instant;
import java.util.Map;

/**
 * One row of a point-in-time read result: the effective value for one feature.
 */
public final class FeatureRow {
    private final String featureName;
    private final int featureVersion;
    private final Map<String, Object> values;
    private final Instant eventTime;
    private final boolean stale;
    private final boolean missing;

    private FeatureRow(String featureName, int featureVersion, Map<String, Object> values,
                       Instant eventTime, boolean stale, boolean missing) {
        this.featureName = featureName;
        this.featureVersion = featureVersion;
        this.values = values;
        this.eventTime = eventTime;
        this.stale = stale;
        this.missing = missing;
    }

    public static FeatureRow found(String name, int version, Map<String, Object> values,
                                   Instant eventTime, boolean stale) {
        return new FeatureRow(name, version, values, eventTime, stale, false);
    }

    public static FeatureRow missing(String name, int version) {
        return new FeatureRow(name, version, null, null, false, true);
    }

    public String getFeatureName() { return featureName; }
    public int getFeatureVersion() { return featureVersion; }
    public Map<String, Object> getValues() { return values; }
    public Instant getEventTime() { return eventTime; }
    public boolean isStale() { return stale; }
    public boolean isMissing() { return missing; }
}

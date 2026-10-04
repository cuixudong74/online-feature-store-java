package com.github.cuixudong74.onlinefeaturestorejava.api;

import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureVector;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureDefinition;

import java.time.Instant;
import java.util.List;

/**
 * Public API of the online feature store.
 */
public interface FeatureService {
    FeatureDefinition registerFeature(FeatureDefinition definition);

    void deprecateFeature(String name, int version, Instant when);

    /** Offline/online write path: append to the log and materialize. */
    void writeFeatures(List<FeatureValue> values);

    /**
     * Point-in-time read. For each requested feature returns the value effective
     * at {@code asOf}; never returns values produced after {@code asOf}.
     * Staleness is handled per the feature's configured policy.
     */
    FeatureVector getFeatures(String entityId, List<FeatureRequest> features, Instant asOf);

    record FeatureRequest(String featureName, Integer featureVersion) {}
}

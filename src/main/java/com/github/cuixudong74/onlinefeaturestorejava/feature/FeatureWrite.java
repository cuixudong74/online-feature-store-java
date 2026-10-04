package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record FeatureWrite(
        String featureName,
        String version,
        long eventTimeEpochMillis,
        String valueJson,
        long revision
) {
}

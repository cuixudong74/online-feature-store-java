package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record FeatureValue(
        ReadStatus status,
        String featureName,
        String version,
        Long eventTimeEpochMillis,
        String valueJson,
        Long ageMillis,
        Long ttlMillis,
        boolean fallback,
        String message
) {
}

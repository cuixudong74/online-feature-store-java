package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record StreamEvent(
        long offset,
        long sequence,
        String commitId,
        long commitTimeEpochMillis,
        FeatureWrite write
) {
}

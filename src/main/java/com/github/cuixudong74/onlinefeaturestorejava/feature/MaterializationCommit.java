package com.github.cuixudong74.onlinefeaturestorejava.feature;

import java.util.List;

public record MaterializationCommit(
        String commitId,
        long commitTimeEpochMillis,
        List<FeatureWrite> writes
) {
}

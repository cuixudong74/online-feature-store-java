package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record VersionRegistration(
        String featureName,
        String version,
        long effectiveFromEpochMillis,
        long ttlMillis,
        FreshnessStrategy freshnessStrategy,
        LateWriteStrategy lateWriteStrategy,
        long allowedLatenessMillis,
        long retentionMillis
) {
}

package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record RegistrationResult(
        RegistrationStatus status,
        String featureName,
        String version,
        String message
) {
}

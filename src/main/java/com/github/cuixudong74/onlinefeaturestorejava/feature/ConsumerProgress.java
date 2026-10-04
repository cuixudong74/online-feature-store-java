package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record ConsumerProgress(
        String consumerId,
        long acknowledgedOffset,
        long highWatermark
) {
}

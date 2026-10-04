package com.github.cuixudong74.onlinefeaturestorejava.feature;

import java.util.List;

public record PollResult(
        String consumerId,
        long nextOffset,
        long highWatermark,
        List<StreamEvent> events
) {
}

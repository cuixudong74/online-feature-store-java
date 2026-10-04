package com.github.cuixudong74.onlinefeaturestorejava.feature;

import java.util.List;

public record CommitResult(
        CommitStatus status,
        long sequence,
        String commitId,
        List<String> reasons,
        RecoveryReport recoveryReport
) {
}

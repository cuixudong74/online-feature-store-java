package com.github.cuixudong74.onlinefeaturestorejava.feature;

public record RecoveryReport(
        long lastCommittedSequence,
        String lastCommitId,
        long recoveredCommitCount,
        long discardedTempFileCount
) {
}

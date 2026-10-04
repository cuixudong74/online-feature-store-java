package com.github.cuixudong74.onlinefeaturestorejava.feature;

import java.util.List;

public interface FeatureStore {

    RegistrationResult registerVersion(VersionRegistration registration);

    RegistrationResult retireVersion(String featureName, String version, long retireTimeEpochMillis);

    CommitResult commit(MaterializationCommit commit);

    FeatureValue read(String consumerId, String featureName, String requestedVersion, long asOfEpochMillis);

    PollResult poll(String consumerId, long offset, int maxEvents);

    void acknowledge(String consumerId, long offset);

    ConsumerProgress consumerProgress(String consumerId);

    RecoveryReport recoveryReport();

    List<VersionRegistration> registeredVersions();
}

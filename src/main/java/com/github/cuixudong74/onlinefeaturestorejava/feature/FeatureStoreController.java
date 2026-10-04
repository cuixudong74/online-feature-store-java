package com.github.cuixudong74.onlinefeaturestorejava.feature;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeatureStoreController {

    private final FeatureStore featureStore;

    public FeatureStoreController(FeatureStore featureStore) {
        this.featureStore = featureStore;
    }

    @PostMapping("/features/versions")
    public RegistrationResult registerVersion(@RequestBody VersionRegistration registration) {
        return featureStore.registerVersion(registration);
    }

    @GetMapping("/features/versions")
    public List<VersionRegistration> registeredVersions() {
        return featureStore.registeredVersions();
    }

    @PostMapping("/features/{featureName}/versions/{version}/retire")
    public RegistrationResult retireVersion(
            @PathVariable String featureName,
            @PathVariable String version,
            @RequestParam long retireTimeEpochMillis) {
        return featureStore.retireVersion(featureName, version, retireTimeEpochMillis);
    }

    @PostMapping("/materialization/commits")
    public CommitResult commit(@RequestBody MaterializationCommit commit) {
        return featureStore.commit(commit);
    }

    @GetMapping("/features/{featureName}/value")
    public FeatureValue read(
            @PathVariable String featureName,
            @RequestParam(required = false) String version,
            @RequestParam long asOfEpochMillis,
            @RequestParam(defaultValue = "default") String consumerId) {
        return featureStore.read(consumerId, featureName, version, asOfEpochMillis);
    }

    @GetMapping("/streams/{consumerId}/poll")
    public PollResult poll(
            @PathVariable String consumerId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "100") int maxEvents) {
        return featureStore.poll(consumerId, offset, maxEvents);
    }

    @PostMapping("/streams/{consumerId}/acknowledge")
    public void acknowledge(@PathVariable String consumerId, @RequestParam long offset) {
        featureStore.acknowledge(consumerId, offset);
    }

    @GetMapping("/streams/{consumerId}/progress")
    public ConsumerProgress consumerProgress(@PathVariable String consumerId) {
        return featureStore.consumerProgress(consumerId);
    }

    @GetMapping("/admin/recovery")
    public RecoveryReport recoveryReport() {
        return featureStore.recoveryReport();
    }
}

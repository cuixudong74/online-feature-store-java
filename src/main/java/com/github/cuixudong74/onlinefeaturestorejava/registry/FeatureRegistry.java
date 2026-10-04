package com.github.cuixudong74.onlinefeaturestorejava.registry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registry of versioned feature definitions.
 */
public interface FeatureRegistry {
    /** Register a new version of a feature. Versions must increase monotonically per name. */
    FeatureDefinition register(FeatureDefinition definition);

    /** Deprecate a version; it stays readable during its retention period. */
    void deprecate(String name, int version, Instant when);

    Optional<FeatureDefinition> get(String name, int version);

    /** The currently active (non-deprecated) version, if any. */
    Optional<FeatureDefinition> getActive(String name);

    /** All known versions of a feature, ascending by version. */
    List<FeatureDefinition> listVersions(String name);
}

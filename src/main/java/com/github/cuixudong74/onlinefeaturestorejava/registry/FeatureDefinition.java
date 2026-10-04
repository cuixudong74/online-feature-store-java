package com.github.cuixudong74.onlinefeaturestorejava.registry;

import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Versioned definition of a feature.
 *
 * <p>A version has a lifecycle window [registeredAt, deprecatedAt). Reads remain
 * valid for a deprecated version during the retention window
 * [deprecatedAt, deprecatedAt + retentionPeriod); after that the version is gone.
 */
public final class FeatureDefinition {
    private final String name;
    private final int version;
    private final List<String> fields;
    private final Duration ttl;
    private final StalenessPolicy stalenessPolicy;
    private final Instant registeredAt;
    private final Instant deprecatedAt;
    private final Duration retentionPeriod;

    public FeatureDefinition(String name, int version, List<String> fields, Duration ttl,
                             StalenessPolicy stalenessPolicy, Instant registeredAt,
                             Instant deprecatedAt, Duration retentionPeriod) {
        this.name = Objects.requireNonNull(name);
        this.version = version;
        this.fields = List.copyOf(fields);
        this.ttl = ttl;
        this.stalenessPolicy = stalenessPolicy == null ? StalenessPolicy.MARK_STALE : stalenessPolicy;
        this.registeredAt = registeredAt;
        this.deprecatedAt = deprecatedAt;
        this.retentionPeriod = retentionPeriod == null ? Duration.ZERO : retentionPeriod;
    }

    public String getName() { return name; }
    public int getVersion() { return version; }
    public List<String> getFields() { return fields; }
    public Duration getTtl() { return ttl; }
    public StalenessPolicy getStalenessPolicy() { return stalenessPolicy; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getDeprecatedAt() { return deprecatedAt; }
    public Duration getRetentionPeriod() { return retentionPeriod; }

    public boolean isActive() { return deprecatedAt == null; }

    /** Whether this version may still serve reads at the given instant. */
    public boolean isReadableAt(Instant when) {
        if (deprecatedAt == null) return true;
        return when.isBefore(deprecatedAt.plus(retentionPeriod));
    }
}

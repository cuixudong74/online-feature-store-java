package com.github.cuixudong74.onlinefeaturestorejava.config;

import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Global defaults; per-feature definitions may override ttl/policy.
 */
@ConfigurationProperties(prefix = "feature-store")
public class FeatureStoreProperties {
    /** Default time-to-live for feature values. */
    private Duration defaultTtl = Duration.ofDays(1);
    /** Default staleness policy when TTL is exceeded. */
    private StalenessPolicy defaultStalenessPolicy = StalenessPolicy.MARK_STALE;
    /** Default retention of deprecated versions. */
    private Duration defaultRetentionPeriod = Duration.ofDays(7);
    /** Materializer batch size (log entries per poll). */
    private int materializerBatchSize = 500;

    public Duration getDefaultTtl() { return defaultTtl; }
    public void setDefaultTtl(Duration defaultTtl) { this.defaultTtl = defaultTtl; }
    public StalenessPolicy getDefaultStalenessPolicy() { return defaultStalenessPolicy; }
    public void setDefaultStalenessPolicy(StalenessPolicy p) { this.defaultStalenessPolicy = p; }
    public Duration getDefaultRetentionPeriod() { return defaultRetentionPeriod; }
    public void setDefaultRetentionPeriod(Duration d) { this.defaultRetentionPeriod = d; }
    public int getMaterializerBatchSize() { return materializerBatchSize; }
    public void setMaterializerBatchSize(int n) { this.materializerBatchSize = n; }
}

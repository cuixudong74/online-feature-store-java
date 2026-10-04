package com.github.cuixudong74.onlinefeaturestorejava.api;

import com.github.cuixudong74.onlinefeaturestorejava.materialization.Materializer;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureRow;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureVector;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureDefinition;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureRegistry;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read/write semantics:
 * <ul>
 *   <li>Writes go to the append-only log first, then are materialized; a read
 *       only ever sees committed, fully applied batches.</li>
 *   <li>Point-in-time reads never return values with eventTime &gt; asOf.</li>
 *   <li>Late writes (eventTime older than the current latest for the timeline)
 *       are accepted and merged deterministically; they become visible to reads
 *       whose asOf covers them and never rewrite history for earlier asOf reads.</li>
 *   <li>Staleness: a value is stale when asOf - eventTime &gt; ttl. The feature's
 *       StalenessPolicy decides the outcome (see StalenessPolicy docs).</li>
 *   <li>Deprecated versions keep serving reads during their retention period;
 *       after retention they read as missing (never an error).</li>
 * </ul>
 */
public class DefaultFeatureService implements FeatureService {
    private final FeatureRegistry registry;
    private final FeatureLog log;
    private final FeatureStore store;
    private final Materializer materializer;
    private final Clock clock;

    public DefaultFeatureService(FeatureRegistry registry, FeatureLog log, FeatureStore store,
                                 Materializer materializer, Clock clock) {
        this.registry = registry;
        this.log = log;
        this.store = store;
        this.materializer = materializer;
        this.clock = clock;
    }

    @Override
    public FeatureDefinition registerFeature(FeatureDefinition definition) {
        return registry.register(definition);
    }

    @Override
    public void deprecateFeature(String name, int version, Instant when) {
        registry.deprecate(name, version, when);
    }

    @Override
    public void writeFeatures(List<FeatureValue> values) {
        if (values.isEmpty()) return;
        log.append(values);
        materializer.materializeOnce();
    }

    @Override
    public FeatureVector getFeatures(String entityId, List<FeatureRequest> features, Instant asOf) {
        List<FeatureRow> rows = new ArrayList<>(features.size());
        for (FeatureRequest req : features) {
            rows.add(resolve(entityId, req, asOf));
        }
        return new FeatureVector(entityId, asOf, rows);
    }

    private FeatureRow resolve(String entityId, FeatureRequest req, Instant asOf) {
        Optional<FeatureDefinition> defOpt = req.featureVersion() != null
                ? registry.get(req.featureName(), req.featureVersion())
                : registry.getActive(req.featureName());
        if (defOpt.isEmpty()) {
            return FeatureRow.missing(req.featureName(), req.featureVersion() == null ? -1 : req.featureVersion());
        }
        FeatureDefinition def = defOpt.get();
        if (!def.isReadableAt(clock.instant())) {
            // Past retention: treat as unknown, never an error.
            return FeatureRow.missing(def.getName(), def.getVersion());
        }
        Optional<FeatureValue> valueOpt =
                store.readAsOf(entityId, def.getName(), def.getVersion(), asOf);
        if (valueOpt.isEmpty()) {
            return FeatureRow.missing(def.getName(), def.getVersion());
        }
        FeatureValue value = valueOpt.get();
        if (!isStale(def, value, asOf)) {
            return FeatureRow.found(def.getName(), def.getVersion(), value.getValues(),
                    value.getEventTime(), false);
        }
        return switch (def.getStalenessPolicy()) {
            case MARK_STALE -> FeatureRow.found(def.getName(), def.getVersion(), value.getValues(),
                    value.getEventTime(), true);
            case FAIL -> FeatureRow.missing(def.getName(), def.getVersion());
            case FALLBACK_TO_PREVIOUS -> fallback(entityId, def, asOf);
        };
    }

    /**
     * 回退策略：当请求版本的最新值过期时，沿版本号向下寻找最近一个
     * 在 asOf 时刻未过期且仍在保留期内的特征版本；全部不可用则视为缺失。
     */
    private FeatureRow fallback(String entityId, FeatureDefinition def, Instant asOf) {
        List<FeatureDefinition> versions = registry.listVersions(def.getName());
        for (int i = versions.size() - 1; i >= 0; i--) {
            FeatureDefinition candidate = versions.get(i);
            if (candidate.getVersion() >= def.getVersion()) continue;
            if (!candidate.isReadableAt(clock.instant())) continue;
            Optional<FeatureValue> valueOpt =
                    store.readAsOf(entityId, candidate.getName(), candidate.getVersion(), asOf);
            if (valueOpt.isPresent() && !isStale(candidate, valueOpt.get(), asOf)) {
                FeatureValue v = valueOpt.get();
                return FeatureRow.found(candidate.getName(), candidate.getVersion(),
                        v.getValues(), v.getEventTime(), false);
            }
        }
        return FeatureRow.missing(def.getName(), def.getVersion());
    }

    private boolean isStale(FeatureDefinition def, FeatureValue value, Instant asOf) {
        return def.getTtl() != null
                && value.getEventTime().plus(def.getTtl()).isBefore(asOf);
    }
}

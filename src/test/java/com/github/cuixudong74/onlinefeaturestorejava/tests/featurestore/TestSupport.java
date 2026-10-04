package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.DefaultFeatureService;
import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService;
import com.github.cuixudong74.onlinefeaturestorejava.materialization.DefaultMaterializer;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureDefinition;
import com.github.cuixudong74.onlinefeaturestorejava.registry.InMemoryFeatureRegistry;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryCheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryFeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryFeatureStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class TestSupport {
    private TestSupport() {}

    static final class Stack {
        final InMemoryFeatureRegistry registry = new InMemoryFeatureRegistry();
        final InMemoryFeatureLog log = new InMemoryFeatureLog();
        final InMemoryFeatureStore store = new InMemoryFeatureStore();
        final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        Clock clock = Clock.systemUTC();
        DefaultMaterializer materializer = newMaterializer();
        FeatureService service = newService();

        DefaultMaterializer newMaterializer() {
            return new DefaultMaterializer(log, store, checkpoints, 100);
        }

        FeatureService newService() {
            return new DefaultFeatureService(registry, log, store, materializer, clock);
        }
    }

    static FeatureDefinition def(String name, int version, Duration ttl, StalenessPolicy policy) {
        return new FeatureDefinition(name, version, List.of("v"), ttl, policy,
                Instant.EPOCH, null, Duration.ofDays(7));
    }

    static FeatureValue val(String entity, String feature, int version,
                            Instant eventTime, Object value, long seq) {
        return new FeatureValue(entity, feature, version, eventTime,
                Map.of("v", value), seq, "test");
    }
}

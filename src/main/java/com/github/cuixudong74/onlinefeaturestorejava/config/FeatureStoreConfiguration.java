package com.github.cuixudong74.onlinefeaturestorejava.config;

import com.github.cuixudong74.onlinefeaturestorejava.api.DefaultFeatureService;
import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService;
import com.github.cuixudong74.onlinefeaturestorejava.materialization.DefaultMaterializer;
import com.github.cuixudong74.onlinefeaturestorejava.materialization.Materializer;
import com.github.cuixudong74.onlinefeaturestorejava.registry.FeatureRegistry;
import com.github.cuixudong74.onlinefeaturestorejava.registry.InMemoryFeatureRegistry;
import com.github.cuixudong74.onlinefeaturestorejava.store.CheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryCheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryFeatureLog;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryFeatureStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(FeatureStoreProperties.class)
public class FeatureStoreConfiguration {

    @Bean
    public FeatureRegistry featureRegistry() {
        return new InMemoryFeatureRegistry();
    }

    @Bean
    public FeatureLog featureLog() {
        return new InMemoryFeatureLog();
    }

    @Bean
    public FeatureStore featureStore() {
        return new InMemoryFeatureStore();
    }

    @Bean
    public CheckpointStore checkpointStore() {
        return new InMemoryCheckpointStore();
    }

    @Bean
    public Materializer materializer(FeatureLog log, FeatureStore store, CheckpointStore checkpoints,
                                     FeatureStoreProperties properties) {
        return new DefaultMaterializer(log, store, checkpoints, properties.getMaterializerBatchSize());
    }

    @Bean
    public FeatureService featureService(FeatureRegistry registry, FeatureLog log, FeatureStore store,
                                         Materializer materializer) {
        return new DefaultFeatureService(registry, log, store, materializer, Clock.systemUTC());
    }
}

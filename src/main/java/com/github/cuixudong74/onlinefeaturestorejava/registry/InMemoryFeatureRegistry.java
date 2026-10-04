package com.github.cuixudong74.onlinefeaturestorejava.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry. Versions per feature name must increase
 * monotonically. Multiple versions may be active simultaneously (e.g. during
 * a migration); getActive returns the highest active version.
 */
public class InMemoryFeatureRegistry implements FeatureRegistry {
    private final Map<String, Map<Integer, FeatureDefinition>> definitions = new ConcurrentHashMap<>();

    @Override
    public FeatureDefinition register(FeatureDefinition definition) {
        Map<Integer, FeatureDefinition> versions =
                definitions.computeIfAbsent(definition.getName(), k -> new ConcurrentHashMap<>());
        synchronized (versions) {
            if (versions.containsKey(definition.getVersion())) {
                throw new IllegalArgumentException("version already registered: "
                        + definition.getName() + " v" + definition.getVersion());
            }
            int maxVersion = versions.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (definition.getVersion() <= maxVersion) {
                throw new IllegalArgumentException("version must increase monotonically: "
                        + definition.getName() + " v" + definition.getVersion() + " <= v" + maxVersion);
            }
            versions.put(definition.getVersion(), definition);
            return definition;
        }
    }

    @Override
    public void deprecate(String name, int version, Instant when) {
        Map<Integer, FeatureDefinition> versions = definitions.get(name);
        if (versions == null) {
            throw new IllegalArgumentException("unknown feature: " + name);
        }
        synchronized (versions) {
            FeatureDefinition existing = versions.get(version);
            if (existing == null) {
                throw new IllegalArgumentException("unknown feature version: " + name + " v" + version);
            }
            if (existing.isActive()) {
                versions.put(version, new FeatureDefinition(
                        existing.getName(), existing.getVersion(), existing.getFields(),
                        existing.getTtl(), existing.getStalenessPolicy(), existing.getRegisteredAt(),
                        when, existing.getRetentionPeriod()));
            }
        }
    }

    @Override
    public Optional<FeatureDefinition> get(String name, int version) {
        Map<Integer, FeatureDefinition> versions = definitions.get(name);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public Optional<FeatureDefinition> getActive(String name) {
        Map<Integer, FeatureDefinition> versions = definitions.get(name);
        if (versions == null) return Optional.empty();
        return versions.values().stream()
                .filter(FeatureDefinition::isActive)
                .max(Comparator.comparingInt(FeatureDefinition::getVersion));
    }

    @Override
    public List<FeatureDefinition> listVersions(String name) {
        Map<Integer, FeatureDefinition> versions = definitions.get(name);
        if (versions == null) return List.of();
        List<FeatureDefinition> list = new ArrayList<>(versions.values());
        list.sort(Comparator.comparingInt(FeatureDefinition::getVersion));
        return list;
    }
}

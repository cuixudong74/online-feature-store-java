package com.github.cuixudong74.onlinefeaturestorejava.feature;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DurableFeatureStore implements FeatureStore {

    private static final int MAX_POLL_EVENTS = 1000;

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Path versionsDirectory;
    private final Path commitsDirectory;
    private final Path consumersDirectory;

    private final Object mutationLock = new Object();
    private final java.util.concurrent.locks.ReentrantReadWriteLock stateLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    private final Map<String, VersionState> versions = new HashMap<>();
    private final Map<FeatureKey, TreeMap<Long, TreeMap<Long, StoredValue>>> values = new HashMap<>();
    private final Map<FeatureKey, Long> watermarks = new HashMap<>();
    private final Map<String, CommitRecord> commitsById = new HashMap<>();
    private final List<StoredEvent> events = new ArrayList<>();
    private long nextSequence;
    private long recoveredCommitCount;
    private long discardedTempFileCount;

    public DurableFeatureStore(
            @Value("${feature-store.dir:data/feature-store}") String directory) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.root = Paths.get(directory);
        this.versionsDirectory = root.resolve("versions");
        this.commitsDirectory = root.resolve("commits");
        this.consumersDirectory = root.resolve("consumers");
        recover();
    }

    @Override
    public RegistrationResult registerVersion(VersionRegistration registration) {
        List<String> errors = validateRegistration(registration);
        if (!errors.isEmpty()) {
            return new RegistrationResult(RegistrationStatus.REJECTED,
                    registration == null ? null : registration.featureName(),
                    registration == null ? null : registration.version(),
                    String.join("; ", errors));
        }

        String versionId = versionId(registration.featureName(), registration.version());
        VersionState newState = new VersionState(registration, null);
        synchronized (mutationLock) {
            VersionState existing = versions.get(versionId);
            if (existing != null) {
                if (existing.retiredAtEpochMillis() != null) {
                    return new RegistrationResult(RegistrationStatus.RETIRED,
                            registration.featureName(), registration.version(),
                            "Version has already been retired and cannot be registered again");
                }
                if (registration.equals(existing.registration())) {
                    return new RegistrationResult(RegistrationStatus.ALREADY_REGISTERED,
                            registration.featureName(), registration.version(),
                            "Version registration is already present with identical configuration");
                }
                return new RegistrationResult(RegistrationStatus.REJECTED,
                        registration.featureName(), registration.version(),
                        "Version already exists with different configuration");
            }
            persistVersion(newState);
            stateLock.writeLock().lock();
            try {
                versions.put(versionId, newState);
            } finally {
                stateLock.writeLock().unlock();
            }
        }
        return new RegistrationResult(RegistrationStatus.REGISTERED,
                registration.featureName(), registration.version(),
                "Version registered");
    }

    @Override
    public RegistrationResult retireVersion(String featureName, String version, long retireTimeEpochMillis) {
        if (isBlank(featureName) || isBlank(version)) {
            return new RegistrationResult(RegistrationStatus.REJECTED, featureName, version,
                    "Feature name and version are required");
        }
        String versionId = versionId(featureName, version);
        synchronized (mutationLock) {
            VersionState existing = versions.get(versionId);
            if (existing == null) {
                return new RegistrationResult(RegistrationStatus.REJECTED,
                        featureName, version, "Version is not registered");
            }
            if (existing.retiredAtEpochMillis() != null) {
                if (existing.retiredAtEpochMillis() == retireTimeEpochMillis) {
                    return new RegistrationResult(RegistrationStatus.ALREADY_RETIRED,
                            featureName, version, "Version is already retired");
                }
                return new RegistrationResult(RegistrationStatus.REJECTED,
                        featureName, version, "Retirement time cannot be changed after retirement");
            }
            if (retireTimeEpochMillis < existing.registration().effectiveFromEpochMillis()) {
                return new RegistrationResult(RegistrationStatus.REJECTED,
                        featureName, version, "Retirement time cannot precede effective time");
            }
            VersionState retired = new VersionState(existing.registration(), retireTimeEpochMillis);
            persistVersion(retired);
            stateLock.writeLock().lock();
            try {
                versions.put(versionId, retired);
            } finally {
                stateLock.writeLock().unlock();
            }
        }
        return new RegistrationResult(RegistrationStatus.RETIRED, featureName, version,
                "Version retired; retention policy remains active");
    }

    @Override
    public CommitResult commit(MaterializationCommit commit) {
        List<String> errors = validateCommitShape(commit);
        if (!errors.isEmpty()) {
            return rejectedCommit(commit, errors);
        }

        synchronized (mutationLock) {
            CommitRecord existingCommit = commitsById.get(commit.commitId());
            if (existingCommit != null) {
                if (!existingCommit.writes().equals(commit.writes())) {
                    return rejectedCommit(commit, List.of("Commit ID was already used with different writes"));
                }
                return new CommitResult(CommitStatus.DUPLICATE, existingCommit.sequence(),
                        commit.commitId(), List.of(
                                "Commit was already applied",
                                "commitTimeEpochMillis is informational and does not affect idempotence"),
                        recoveryReport());
            }

            CommitPlan plan = planCommit(commit);
            if (!plan.reasons().isEmpty()) {
                return new CommitResult(CommitStatus.REJECTED, -1, commit.commitId(),
                        List.copyOf(plan.reasons()), recoveryReport());
            }

            long sequence = nextSequence;
            CommitRecord record = new CommitRecord(sequence, commit.commitId(),
                    commit.commitTimeEpochMillis(), List.copyOf(commit.writes()));
            persistCommit(record);
            nextSequence = sequence + 1;

            stateLock.writeLock().lock();
            try {
                applyPlannedCommit(record, plan);
                commitsById.put(commit.commitId(), record);
                for (FeatureWrite write : commit.writes()) {
                    events.add(new StoredEvent(events.size(), sequence,
                            commit.commitId(), commit.commitTimeEpochMillis(), write));
                }
            } finally {
                stateLock.writeLock().unlock();
            }

            List<String> reasons = new ArrayList<>();
            if (plan.duplicateWrites() > 0) {
                reasons.add("ignoredExactDuplicateWrites=" + plan.duplicateWrites());
            }
            if (plan.obsoleteWrites() > 0) {
                reasons.add("ignoredLowerRevisionWrites=" + plan.obsoleteWrites());
            }
            return new CommitResult(CommitStatus.APPLIED, sequence, commit.commitId(),
                    List.copyOf(reasons), recoveryReport());
        }
    }

    @Override
    public FeatureValue read(String consumerId, String featureName, String requestedVersion, long asOfEpochMillis) {
        if (isBlank(consumerId)) {
            return FeatureValues.invalid(featureName, requestedVersion, "consumerId is required");
        }
        if (asOfEpochMillis <= 0) {
            return FeatureValues.invalid(featureName, requestedVersion, "asOfEpochMillis must be positive");
        }
        if (isBlank(featureName)) {
            return FeatureValues.invalid(featureName, requestedVersion, "featureName is required");
        }

        stateLock.readLock().lock();
        try {
            if (!featureExists(featureName)) {
                return new FeatureValue(ReadStatus.UNKNOWN_FEATURE, featureName,
                        requestedVersion, null, null, null, null, false,
                        "No registered feature uses this name");
            }

            VersionState selected;
            if (isBlank(requestedVersion)) {
                Optional<VersionState> automatic = versions.values().stream()
                        .filter(state -> state.registration().featureName().equals(featureName))
                        .filter(state -> state.registration().effectiveFromEpochMillis() <= asOfEpochMillis)
                        .filter(state -> isReadableAt(state, asOfEpochMillis))
                        .max(versionSelectionOrder());
                if (automatic.isEmpty()) {
                    return new FeatureValue(ReadStatus.NO_DATA, featureName, null, null,
                            null, null, null, false, "No readable version is effective at the requested time");
                }
                selected = automatic.get();
            } else {
                VersionState explicit = versions.get(versionId(featureName, requestedVersion));
                if (explicit == null) {
                    return new FeatureValue(ReadStatus.UNKNOWN_VERSION, featureName,
                            requestedVersion, null, null, null, null, false,
                            "Requested version is not registered");
                }
                if (explicit.registration().effectiveFromEpochMillis() > asOfEpochMillis) {
                    return new FeatureValue(ReadStatus.NO_DATA, featureName,
                            requestedVersion, null, null, null,
                            explicit.registration().ttlMillis(), false,
                            "Requested version is not effective at the requested time");
                }
                if (!isReadableAt(explicit, asOfEpochMillis)) {
                    return new FeatureValue(ReadStatus.VERSION_RETENTION_EXPIRED, featureName,
                            requestedVersion, null, null, null,
                            explicit.registration().ttlMillis(), false,
                            "Retired version is outside its retention window");
                }
                selected = explicit;
            }

            VersionRegistration registration = selected.registration();
            Optional<PointCandidate> candidate = latestCandidate(
                    featureName, registration.version(), asOfEpochMillis);
            if (candidate.isPresent() && isFresh(candidate.get(), registration, asOfEpochMillis)) {
                return toFeatureValue(ReadStatus.FOUND, candidate.get(), registration, false,
                        "Value was committed before and is fresh at the requested time", asOfEpochMillis);
            }

            if (registration.freshnessStrategy() == FreshnessStrategy.MARK_STALE) {
                if (candidate.isPresent()) {
                    return toFeatureValue(ReadStatus.STALE, candidate.get(), registration, false,
                            "Value is older than ttlMillis; MARK_STALE retains the stale value",
                            asOfEpochMillis);
                }
                return noData(featureName, registration.version(), registration.ttlMillis(),
                        "No value exists at or before the requested time");
            }

            Optional<FallbackValue> fallback = findPreviousFreshVersion(featureName, selected, asOfEpochMillis);
            if (fallback.isPresent()) {
                PointCandidate previous = fallback.get().candidate();
                VersionRegistration previousVersion = fallback.get().version();
                return toFeatureValue(ReadStatus.FOUND, previous, previousVersion, true,
                        "Selected version was missing or stale; returned the nearest previous fresh version",
                        asOfEpochMillis);
            }

            if (candidate.isPresent()) {
                return toFeatureValue(ReadStatus.EXPIRED, candidate.get(), registration, false,
                        "No fresh previous version is available for FALLBACK_PREVIOUS",
                        asOfEpochMillis);
            }
            return noData(featureName, registration.version(), registration.ttlMillis(),
                    "No current or fallback value is available at the requested time");
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public PollResult poll(String consumerId, long offset, int maxEvents) {
        if (isBlank(consumerId)) {
            throw badRequest("consumerId is required");
        }
        if (offset < 0) {
            throw badRequest("offset must be zero or positive");
        }
        int limit = Math.max(1, Math.min(maxEvents, MAX_POLL_EVENTS));
        stateLock.readLock().lock();
        try {
            long highWatermark = events.size();
            List<StreamEvent> batch = new ArrayList<>();
            for (int index = (int) Math.min(offset, highWatermark);
                 index < highWatermark && batch.size() < limit; index++) {
                StoredEvent event = events.get(index);
                batch.add(new StreamEvent(event.offset(), event.sequence(), event.commitId(),
                        event.commitTimeEpochMillis(), event.write()));
            }
            return new PollResult(consumerId, offset, highWatermark, List.copyOf(batch));
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void acknowledge(String consumerId, long offset) {
        if (isBlank(consumerId)) {
            throw badRequest("consumerId is required");
        }
        stateLock.readLock().lock();
        long highWatermark;
        try {
            highWatermark = events.size();
        } finally {
            stateLock.readLock().unlock();
        }
        if (offset < 0 || offset >= highWatermark) {
            throw badRequest("acknowledged offset must be an existing stream offset");
        }
        Path file = consumerPath(consumerId);
        synchronized (mutationLock) {
            atomicWriteString(file, Long.toString(offset));
        }
    }

    @Override
    public ConsumerProgress consumerProgress(String consumerId) {
        if (isBlank(consumerId)) {
            throw badRequest("consumerId is required");
        }
        Path file = consumerPath(consumerId);
        long acknowledgedOffset = -1;
        if (Files.isRegularFile(file)) {
            try {
                acknowledgedOffset = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read consumer progress for " + consumerId, e);
            }
        }
        stateLock.readLock().lock();
        try {
            return new ConsumerProgress(consumerId, acknowledgedOffset, events.size());
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public RecoveryReport recoveryReport() {
        stateLock.readLock().lock();
        try {
            String lastCommitId = events.isEmpty() ? null : events.get(events.size() - 1).commitId();
            if (lastCommitId == null && nextSequence > 0) {
                lastCommitId = commitsById.values().stream()
                        .filter(record -> record.sequence() == nextSequence - 1)
                        .findFirst()
                        .map(CommitRecord::commitId)
                        .orElse(null);
            }
            return new RecoveryReport(Math.max(0, nextSequence - 1), lastCommitId,
                    recoveredCommitCount, discardedTempFileCount);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public List<VersionRegistration> registeredVersions() {
        stateLock.readLock().lock();
        try {
            return versions.values().stream()
                    .sorted(Comparator.comparing((VersionState state) -> state.registration().featureName())
                            .thenComparing(state -> state.registration().version()))
                    .map(VersionState::registration)
                    .toList();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private void recover() {
        try {
            Files.createDirectories(versionsDirectory);
            Files.createDirectories(commitsDirectory);
            Files.createDirectories(consumersDirectory);
            discardedTempFileCount += deleteTempFiles(root);

            try (Stream<Path> files = Files.list(versionsDirectory)) {
                List<Path> sorted = files
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
                for (Path file : sorted) {
                    VersionState state = readJson(file, VersionState.class);
                    versions.put(versionId(state.registration().featureName(),
                            state.registration().version()), state);
                }
            }

            List<CommitRecord> records;
            try (Stream<Path> files = Files.list(commitsDirectory)) {
                records = files
                        .filter(path -> path.getFileName().toString().startsWith("commit-"))
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(path -> readJson(path, CommitRecord.class))
                        .sorted(Comparator.comparingLong(CommitRecord::sequence))
                        .toList();
            }
            for (CommitRecord record : records) {
                replayCommit(record);
            }
            recoveredCommitCount = records.size();
            nextSequence = records.stream().mapToLong(CommitRecord::sequence).max().orElse(-1) + 1;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to recover durable feature store", e);
        }
    }

    private void replayCommit(CommitRecord record) {
        if (commitsById.put(record.commitId(), record) != null) {
            throw new IllegalStateException("Duplicate committed commit ID recovered: " + record.commitId());
        }
        for (FeatureWrite write : record.writes()) {
            FeatureKey key = new FeatureKey(write.featureName(), write.version());
            VersionState state = versions.get(versionId(key.featureName(), key.version()));
            if (state == null) {
                throw new IllegalStateException("Committed write refers to missing version: " + key);
            }
            StoredValue stored = new StoredValue(write.valueJson(), write.revision(),
                    record.sequence(), record.commitId(), record.commitTimeEpochMillis());
            TreeMap<Long, TreeMap<Long, StoredValue>> byEventTime =
                    values.computeIfAbsent(key, ignored -> new TreeMap<>());
            TreeMap<Long, StoredValue> byRevision =
                    byEventTime.computeIfAbsent(write.eventTimeEpochMillis(), ignored -> new TreeMap<>());
            StoredValue previous = byRevision.put(write.revision(), stored);
            if (previous != null && !previous.valueJson().equals(write.valueJson())) {
                throw new IllegalStateException("Conflicting committed revision recovered for " + key);
            }
            watermarks.merge(key, write.eventTimeEpochMillis(), Math::max);
            events.add(new StoredEvent(events.size(), record.sequence(),
                    record.commitId(), record.commitTimeEpochMillis(), write));
        }
    }

    private CommitPlan planCommit(MaterializationCommit commit) {
        List<String> reasons = new ArrayList<>();
        Map<FeatureKey, Map<Long, Map<Long, String>>> batch = new HashMap<>();
        for (FeatureWrite write : commit.writes()) {
            VersionState state = versions.get(versionId(write.featureName(), write.version()));
            if (state == null) {
                reasons.add("Unknown feature/version: " + write.featureName() + ":" + write.version());
                continue;
            }
            FeatureKey key = new FeatureKey(write.featureName(), write.version());
            String existingValue = batch.computeIfAbsent(key, ignored -> new HashMap<>())
                    .computeIfAbsent(write.eventTimeEpochMillis(), ignored -> new HashMap<>())
                    .put(write.revision(), write.valueJson());
            if (existingValue != null && !existingValue.equals(write.valueJson())) {
                reasons.add("Conflicting values at same feature/version/event/revision: " + key);
            }
        }
        if (!reasons.isEmpty()) {
            return new CommitPlan(Map.of(), 0, 0, reasons);
        }

        Map<WriteIdentity, PlannedWrite> planned = new HashMap<>();
        int duplicateWrites = 0;
        int obsoleteWrites = 0;
        for (FeatureWrite write : commit.writes()) {
            FeatureKey key = new FeatureKey(write.featureName(), write.version());
            VersionState state = versions.get(versionId(key.featureName(), key.version()));
            VersionRegistration registration = state.registration();
            Long currentHighWatermark = watermarks.get(key);
            if (currentHighWatermark != null
                    && write.eventTimeEpochMillis() < currentHighWatermark
                    && currentHighWatermark - write.eventTimeEpochMillis()
                    > registration.allowedLatenessMillis()) {
                if (registration.lateWriteStrategy() == LateWriteStrategy.REJECT) {
                    reasons.add("Late write rejected for " + key + "; eventTime="
                            + write.eventTimeEpochMillis() + ", watermark=" + currentHighWatermark
                            + ", allowedLatenessMillis=" + registration.allowedLatenessMillis());
                    continue;
                }
            }

            TreeMap<Long, TreeMap<Long, StoredValue>> byEventTime = values.get(key);
            StoredValue existing = byEventTime == null ? null
                    : Optional.ofNullable(byEventTime.get(write.eventTimeEpochMillis()))
                    .map(map -> map.get(write.revision()))
                    .orElse(null);
            if (existing != null && existing.valueJson().equals(write.valueJson())) {
                duplicateWrites++;
                continue;
            }

            long highestRevision = byEventTime == null
                    ? Long.MIN_VALUE
                    : Optional.ofNullable(byEventTime.get(write.eventTimeEpochMillis()))
                    .map(TreeMap::lastKey).orElse(Long.MIN_VALUE);
            if (write.revision() < highestRevision) {
                obsoleteWrites++;
                continue;
            }
            if (existing != null) {
                reasons.add("Conflicting persisted value at same feature/version/event/revision for " + key);
                continue;
            }

            WriteIdentity identity = new WriteIdentity(key, write.eventTimeEpochMillis(), write.revision());
            if (planned.put(identity, new PlannedWrite(write)) != null) {
                duplicateWrites++;
            }
        }
        return new CommitPlan(Map.copyOf(planned), duplicateWrites, obsoleteWrites, reasons);
    }

    private void applyPlannedCommit(CommitRecord record, CommitPlan plan) {
        for (PlannedWrite planned : plan.plannedWrites().values()) {
            FeatureWrite write = planned.write();
            FeatureKey key = new FeatureKey(write.featureName(), write.version());
            StoredValue stored = new StoredValue(write.valueJson(), write.revision(),
                    record.sequence(), record.commitId(), record.commitTimeEpochMillis());
            values.computeIfAbsent(key, ignored -> new TreeMap<>())
                    .computeIfAbsent(write.eventTimeEpochMillis(), ignored -> new TreeMap<>())
                    .put(write.revision(), stored);
            watermarks.merge(key, write.eventTimeEpochMillis(), Math::max);
        }
    }

    private Optional<PointCandidate> latestCandidate(String featureName, String version, long asOfEpochMillis) {
        FeatureKey key = new FeatureKey(featureName, version);
        TreeMap<Long, TreeMap<Long, StoredValue>> byEventTime = values.get(key);
        if (byEventTime == null) {
            return Optional.empty();
        }
        Map.Entry<Long, TreeMap<Long, StoredValue>> eventEntry = byEventTime.floorEntry(asOfEpochMillis);
        if (eventEntry == null) {
            return Optional.empty();
        }
        Map.Entry<Long, StoredValue> revisionEntry = eventEntry.getValue().lastEntry();
        return Optional.of(new PointCandidate(eventEntry.getKey(), revisionEntry.getKey(), revisionEntry.getValue()));
    }

    private Optional<FallbackValue> findPreviousFreshVersion(
            String featureName, VersionState selected, long asOfEpochMillis) {
        return versions.values().stream()
                .filter(state -> state.registration().featureName().equals(featureName))
                .filter(state -> state != selected)
                .filter(state -> state.registration().effectiveFromEpochMillis()
                        < selected.registration().effectiveFromEpochMillis())
                .filter(state -> state.registration().effectiveFromEpochMillis() <= asOfEpochMillis)
                .filter(state -> isReadableAt(state, asOfEpochMillis))
                .sorted(versionSelectionOrder().reversed())
                .map(state -> {
                    Optional<PointCandidate> candidate = latestCandidate(
                            featureName, state.registration().version(), asOfEpochMillis);
                    if (candidate.isPresent()
                            && isFresh(candidate.get(), state.registration(), asOfEpochMillis)) {
                        return Optional.of(new FallbackValue(state.registration(), candidate.get()));
                    }
                    return Optional.<FallbackValue>empty();
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Comparator<VersionState> versionSelectionOrder() {
        return Comparator.comparingLong((VersionState state) ->
                state.registration().effectiveFromEpochMillis())
                .thenComparing(state -> state.registration().version());
    }

    private boolean isFresh(PointCandidate candidate, VersionRegistration registration, long asOfEpochMillis) {
        return asOfEpochMillis - candidate.eventTimeEpochMillis() <= registration.ttlMillis();
    }

    private boolean isReadableAt(VersionState state, long asOfEpochMillis) {
        if (state.retiredAtEpochMillis() == null) {
            return true;
        }
        return asOfEpochMillis <= state.retiredAtEpochMillis()
                + state.registration().retentionMillis();
    }

    private boolean featureExists(String featureName) {
        return versions.values().stream()
                .anyMatch(state -> state.registration().featureName().equals(featureName));
    }

    private FeatureValue toFeatureValue(ReadStatus status, PointCandidate candidate,
                                        VersionRegistration registration, boolean fallback,
                                        String message, long asOfEpochMillis) {
        return new FeatureValue(status, registration.featureName(), registration.version(),
                candidate.eventTimeEpochMillis(), candidate.value().valueJson(),
                asOfEpochMillis - candidate.eventTimeEpochMillis(),
                registration.ttlMillis(), fallback, message);
    }

    private FeatureValue noData(String featureName, String version, Long ttlMillis, String message) {
        return new FeatureValue(ReadStatus.NO_DATA, featureName, version, null,
                null, null, ttlMillis, false, message);
    }

    private List<String> validateRegistration(VersionRegistration registration) {
        List<String> errors = new ArrayList<>();
        if (registration == null) {
            return List.of("registration body is required");
        }
        if (isBlank(registration.featureName())) {
            errors.add("featureName is required");
        }
        if (isBlank(registration.version())) {
            errors.add("version is required");
        }
        if (registration.effectiveFromEpochMillis() <= 0) {
            errors.add("effectiveFromEpochMillis must be positive");
        }
        if (registration.ttlMillis() < 0) {
            errors.add("ttlMillis cannot be negative");
        }
        if (registration.allowedLatenessMillis() < 0) {
            errors.add("allowedLatenessMillis cannot be negative");
        }
        if (registration.retentionMillis() < 0) {
            errors.add("retentionMillis cannot be negative");
        }
        if (registration.freshnessStrategy() == null) {
            errors.add("freshnessStrategy is required");
        }
        if (registration.lateWriteStrategy() == null) {
            errors.add("lateWriteStrategy is required");
        }
        return errors;
    }

    private List<String> validateCommitShape(MaterializationCommit commit) {
        List<String> errors = new ArrayList<>();
        if (commit == null) {
            return List.of("commit body is required");
        }
        if (isBlank(commit.commitId())) {
            errors.add("commitId is required");
        }
        if (commit.commitTimeEpochMillis() <= 0) {
            errors.add("commitTimeEpochMillis must be positive");
        }
        if (commit.writes() == null || commit.writes().isEmpty()) {
            errors.add("writes must contain at least one feature write");
            return errors;
        }
        Map<WriteIdentity, String> identityValues = new HashMap<>();
        for (int index = 0; index < commit.writes().size(); index++) {
            FeatureWrite write = commit.writes().get(index);
            if (write == null) {
                errors.add("writes[" + index + "] is null");
                continue;
            }
            if (isBlank(write.featureName())) {
                errors.add("writes[" + index + "].featureName is required");
            }
            if (isBlank(write.version())) {
                errors.add("writes[" + index + "].version is required");
            }
            if (write.eventTimeEpochMillis() <= 0) {
                errors.add("writes[" + index + "].eventTimeEpochMillis must be positive");
            }
            if (write.revision() < 0) {
                errors.add("writes[" + index + "].revision cannot be negative");
            }
            if (isBlank(write.valueJson()) || !isValidJson(write.valueJson())) {
                errors.add("writes[" + index + "].valueJson must contain valid JSON");
            }
            WriteIdentity identity = new WriteIdentity(
                    new FeatureKey(write.featureName(), write.version()),
                    write.eventTimeEpochMillis(), write.revision());
            String previousIdentityValue = identityValues.put(identity, write.valueJson());
            if (previousIdentityValue != null && !previousIdentityValue.equals(write.valueJson())) {
                errors.add("writes[" + index
                        + "] conflicts with another write at same feature/version/event/revision");
            }
        }
        return errors;
    }

    private boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private CommitResult rejectedCommit(MaterializationCommit commit, List<String> reasons) {
        return new CommitResult(CommitStatus.REJECTED, -1,
                commit == null ? null : commit.commitId(), List.copyOf(reasons), recoveryReport());
    }


    private void persistCommit(CommitRecord record) {
        Path target = commitsDirectory.resolve(String.format("commit-%020d.json", record.sequence()));
        atomicWriteJson(target, record);
    }

    private void persistVersion(VersionState state) {
        Path target = versionsDirectory.resolve(URLEncoder.encode(
                state.registration().featureName() + "|" + state.registration().version(),
                StandardCharsets.UTF_8) + ".json");
        atomicWriteJson(target, state);
    }

    private void atomicWriteJson(Path target, Object value) {
        try {
            atomicWriteString(target, objectMapper.writeValueAsString(value));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize " + target, e);
        }
    }

    private void atomicWriteString(Path target, String value) {
        Path temp = null;
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw new UncheckedIOException("Failed to atomically write " + target, e);
        }
    }

    private <T> T readJson(Path file, Class<T> type) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read durable file " + file, e);
        }
    }

    private long deleteTempFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> tempFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .toList();
            for (Path file : tempFiles) {
                Files.deleteIfExists(file);
            }
            return tempFiles.size();
        }
    }

    private Path consumerPath(String consumerId) {
        return consumersDirectory.resolve(URLEncoder.encode(consumerId, StandardCharsets.UTF_8) + ".json");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String versionId(String featureName, String version) {
        return featureName + "\u0000" + version;
    }

    private record FeatureKey(String featureName, String version) {
    }

    private record VersionState(VersionRegistration registration, Long retiredAtEpochMillis) {
    }

    private record StoredValue(String valueJson, long revision, long commitSequence,
                               String commitId, long commitTimeEpochMillis) {
    }

    private record StoredEvent(long offset, long sequence, String commitId,
                               long commitTimeEpochMillis, FeatureWrite write) {
    }

    private record CommitRecord(long sequence, String commitId, long commitTimeEpochMillis,
                                List<FeatureWrite> writes) {
        private MaterializationCommit toMaterializationCommit() {
            return new MaterializationCommit(commitId, commitTimeEpochMillis, List.copyOf(writes));
        }
    }

    private record PlannedWrite(FeatureWrite write) {
    }

    private record CommitPlan(Map<WriteIdentity, PlannedWrite> plannedWrites,
                              int duplicateWrites, int obsoleteWrites, List<String> reasons) {
    }

    private record PointCandidate(long eventTimeEpochMillis, long revision, StoredValue value) {
    }

    private record FallbackValue(VersionRegistration version, PointCandidate candidate) {
    }

    private static final class WriteIdentity {
        private final FeatureKey key;
        private final long eventTimeEpochMillis;
        private final long revision;

        private WriteIdentity(FeatureKey key, long eventTimeEpochMillis, long revision) {
            this.key = key;
            this.eventTimeEpochMillis = eventTimeEpochMillis;
            this.revision = revision;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WriteIdentity that)) {
                return false;
            }
            return eventTimeEpochMillis == that.eventTimeEpochMillis
                    && revision == that.revision
                    && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, eventTimeEpochMillis, revision);
        }
    }

    private static final class FeatureValues {
        private static FeatureValue invalid(String featureName, String version, String message) {
            return new FeatureValue(ReadStatus.INVALID_REQUEST, featureName, version,
                    null, null, null, null, false, message);
        }
    }
}

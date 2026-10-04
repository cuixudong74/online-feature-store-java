package com.github.cuixudong74.onlinefeaturestorejava.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFeatureStoreTest {

    @TempDir
    Path directory;

    @Test
    void readNeverReturnsAValueProducedAfterRequestedTime() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "age", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);

        assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                "c-1000", 5_000,
                List.of(write("age", "v1", 1000, "18", 0)))).status());
        assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                "c-2000", 5_001,
                List.of(write("age", "v1", 2000, "19", 0)))).status());

        assertEquals(ReadStatus.NO_DATA, store.read("online", "age", null, 999).status());
        FeatureValue atThousand = store.read("online", "age", null, 1000);
        assertEquals(ReadStatus.FOUND, atThousand.status());
        assertEquals("18", atThousand.valueJson());
        assertEquals(1000L, atThousand.eventTimeEpochMillis());

        FeatureValue atBoundary = store.read("online", "age", null, 1999);
        assertEquals(ReadStatus.FOUND, atBoundary.status());
        assertEquals("18", atBoundary.valueJson());

        FeatureValue atTwoThousand = store.read("online", "age", null, 2000);
        assertEquals(ReadStatus.FOUND, atTwoThousand.status());
        assertEquals("19", atTwoThousand.valueJson());
    }

    @Test
    void outOfOrderRevisionsAtSameEventTimeConvergeToHighestRevision() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "score", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);

        assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                "higher-first", 5_000,
                List.of(write("score", "v1", 1000, "2", 1)))).status());
        CommitResult olderRevision = store.commit(new MaterializationCommit(
                "lower-after", 5_001,
                List.of(write("score", "v1", 1000, "1", 0))));
        assertEquals(CommitStatus.APPLIED, olderRevision.status());
        assertTrue(olderRevision.reasons().get(0).contains("ignoredLowerRevisionWrites=1"));

        assertEquals("2", store.read("online", "score", null, 1000).valueJson());

        DurableFeatureStore reordered = new DurableFeatureStore(directory.toString());
        assertEquals("2", reordered.read("online", "score", null, 1000).valueJson());
    }


    @Test
    void duplicateWritesAndDuplicateCommitDoNotChangeState() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "city", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);

        MaterializationCommit commit = new MaterializationCommit(
                "duplicate-commit", 5_000,
                List.of(write("city", "v1", 1000, "42", 0)));
        assertEquals(CommitStatus.APPLIED, store.commit(commit).status());
        assertEquals(CommitStatus.DUPLICATE, store.commit(commit).status());

        MaterializationCommit exactWriteAgain = new MaterializationCommit(
                "exact-write-again", 5_001,
                List.of(write("city", "v1", 1000, "42", 0)));
        CommitResult repeated = store.commit(exactWriteAgain);
        assertEquals(CommitStatus.APPLIED, repeated.status());
        assertTrue(repeated.reasons().get(0).contains("ignoredExactDuplicateWrites=1"));

        PollResult firstPoll = store.poll("audit-a", 0, 10);
        PollResult secondPoll = store.poll("audit-b", 0, 10);
        assertEquals(2, firstPoll.events().size());
        assertEquals(2, secondPoll.events().size());
        assertEquals("42", store.read("online", "city", null, 1000).valueJson());
    }

    @Test
    void lateWritePolicyRejectsEventsBeyondAllowedLateness() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        RegistrationResult registration = store.registerVersion(new VersionRegistration(
                "amount", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.REJECT, 100, 100_000));
        assertEquals(RegistrationStatus.REGISTERED, registration.status());

        assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                "watermark", 5_000,
                List.of(write("amount", "v1", 1000, "10", 0)))).status());
        CommitResult withinWindow = store.commit(new MaterializationCommit(
                "within-late-window", 5_001,
                List.of(write("amount", "v1", 1000, "11", 1))));
        assertEquals(CommitStatus.APPLIED, withinWindow.status());

        CommitResult tooLate = store.commit(new MaterializationCommit(
                "too-late", 5_002,
                List.of(write("amount", "v1", 800, "9", 0))));
        assertEquals(CommitStatus.REJECTED, tooLate.status());
        assertTrue(tooLate.reasons().get(0).startsWith("Late write rejected"));
        assertEquals("11", store.read("online", "amount", null, 1000).valueJson());
    }


    @Test
    void markStaleKeepsValueAfterTtlBoundary() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        RegistrationResult registration = store.registerVersion(new VersionRegistration(
                "temperature", "v1", 1000, 100, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, 10_000, 100_000));
        assertEquals(RegistrationStatus.REGISTERED, registration.status());
        store.commit(new MaterializationCommit("temperature-1000", 5_000,
                List.of(write("temperature", "v1", 1000, "20", 0))));

        assertEquals(ReadStatus.FOUND, store.read("online", "temperature", "v1", 1100).status());
        FeatureValue stale = store.read("online", "temperature", "v1", 1101);
        assertEquals(ReadStatus.STALE, stale.status());
        assertEquals("20", stale.valueJson());
        assertEquals(101L, stale.ageMillis());
        assertEquals(100L, stale.ttlMillis());
    }

    @Test
    void fallbackPreviousReturnsFreshOldVersionAndCanExpire() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "price", "v1", 1000, 1_000, FreshnessStrategy.FALLBACK_PREVIOUS,
                LateWriteStrategy.ACCEPT, 10_000, 100_000)).status());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "price", "v2", 1500, 100, FreshnessStrategy.FALLBACK_PREVIOUS,
                LateWriteStrategy.ACCEPT, 10_000, 100_000)).status());
        store.commit(new MaterializationCommit("price-v1", 5_000,
                List.of(write("price", "v1", 1000, "100", 0))));
        store.commit(new MaterializationCommit("price-v2", 5_001,
                List.of(write("price", "v2", 1500, "110", 0))));

        FeatureValue automaticAtV2 = store.read("online", "price", null, 1500);
        assertEquals(ReadStatus.FOUND, automaticAtV2.status());
        assertEquals("v2", automaticAtV2.version());
        assertEquals("110", automaticAtV2.valueJson());

        FeatureValue fallback = store.read("online", "price", null, 1601);
        assertEquals(ReadStatus.FOUND, fallback.status());
        assertEquals("v1", fallback.version());
        assertEquals("100", fallback.valueJson());
        assertTrue(fallback.fallback());

        FeatureValue expired = store.read("online", "price", "v2", 2101);
        assertEquals(ReadStatus.EXPIRED, expired.status());
    }


    @Test
    void restartRecoversOnlyCommittedStateAndContinuesAfterLastSequence() throws Exception {
        DurableFeatureStore first = new DurableFeatureStore(directory.toString());
        register(first, "balance", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);
        MaterializationCommit firstCommit = new MaterializationCommit(
                "first-commit", 5_000, List.of(write("balance", "v1", 1000, "1", 0)));
        assertEquals(0, first.commit(firstCommit).sequence());
        first.acknowledge("auditor", 0);

        Path dangling = directory.resolve("commits").resolve("commit-99999999999999999999.json.tmp");
        Files.writeString(dangling, "{\"incomplete\":true}");

        DurableFeatureStore restarted = new DurableFeatureStore(directory.toString());
        RecoveryReport report = restarted.recoveryReport();
        assertEquals(0, report.lastCommittedSequence());
        assertEquals("first-commit", report.lastCommitId());
        assertEquals(1, report.recoveredCommitCount());
        assertEquals(1, report.discardedTempFileCount());
        assertEquals("1", restarted.read("online", "balance", null, 1000).valueJson());
        assertEquals(CommitStatus.DUPLICATE, restarted.commit(firstCommit).status());

        CommitResult next = restarted.commit(new MaterializationCommit(
                "second-commit", 5_001, List.of(write("balance", "v1", 2000, "2", 0))));
        assertEquals(CommitStatus.APPLIED, next.status());
        assertEquals(1, next.sequence());
        assertEquals(1, next.recoveryReport().recoveredCommitCount());
        assertEquals(0, restarted.consumerProgress("auditor").acknowledgedOffset());
        assertEquals(2, restarted.consumerProgress("auditor").highWatermark());
    }


    @Test
    void consumersMaintainIndependentStreamProgressWithoutSlowingWriters() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "signal", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);
        for (int i = 0; i < 10; i++) {
            assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                    "signal-" + i, 5_000 + i,
                    List.of(write("signal", "v1", 1000 + i, Integer.toString(i), 0)))).status());
        }

        PollResult slow = store.poll("slow", 0, 2);
        PollResult fast = store.poll("fast", 0, 10);
        assertEquals(2, slow.events().size());
        assertEquals(10, fast.events().size());
        store.acknowledge("fast", 9);

        PollResult slowContinued = store.poll("slow", 2, 10);
        assertEquals(8, slowContinued.events().size());
        assertEquals(9, store.consumerProgress("fast").acknowledgedOffset());
        assertEquals(-1, store.consumerProgress("slow").acknowledgedOffset());
        assertEquals(10, store.consumerProgress("slow").highWatermark());
    }

    @Test
    void addingAndRetiringVersionsKeepsExistingReadsWithinRetentionWindow() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "model", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, 10_000, 1000)).status());
        store.commit(new MaterializationCommit("model-v1", 5_000,
                List.of(write("model", "v1", 1000, "1", 0))));
        assertEquals("1", store.read("online", "model", "v1", 1000).valueJson());

        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "model", "v2", 1500, 60_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, 10_000, 1000)).status());
        store.commit(new MaterializationCommit("model-v2", 5_001,
                List.of(write("model", "v2", 1500, "2", 0))));
        assertEquals(RegistrationStatus.RETIRED,
                store.retireVersion("model", "v1", 1600).status());

        FeatureValue oldWithinRetention = store.read("online", "model", "v1", 2000);
        assertEquals(ReadStatus.FOUND, oldWithinRetention.status());
        assertEquals("1", oldWithinRetention.valueJson());

        FeatureValue automatic = store.read("online", "model", null, 2000);
        assertEquals(ReadStatus.FOUND, automatic.status());
        assertEquals("v2", automatic.version());
        assertEquals("2", automatic.valueJson());

        FeatureValue oldAfterRetention = store.read("online", "model", "v1", 2601);
        assertEquals(ReadStatus.VERSION_RETENTION_EXPIRED, oldAfterRetention.status());
    }


    @Test
    void concurrentCommitsAndReadsObserveCommittedSnapshotsOnly() throws Exception {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "atomic", "v1", 1, 1_000_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, Long.MAX_VALUE, 1_000_000)).status());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "sibling", "v1", 1, 1_000_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, Long.MAX_VALUE, 1_000_000)).status());

        int writers = 8;
        int commitsPerWriter = 25;
        ExecutorService executor = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finishedWriters = new CountDownLatch(writers);
        AtomicBoolean failed = new AtomicBoolean(false);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        for (int writer = 0; writer < writers; writer++) {
            int writerId = writer;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < commitsPerWriter; i++) {
                        long eventTime = writerId * 10_000L + i + 1;
                        CommitResult result = store.commit(new MaterializationCommit(
                                "commit-" + writerId + "-" + i, 5_000L + i,
                                List.of(
                                        write("atomic", "v1", eventTime,
                                                "{\"w\":" + writerId + ",\"i\":" + i + "}", 0),
                                        write("sibling", "v1", eventTime, Integer.toString(writerId), 0))));
                        if (result.status() != CommitStatus.APPLIED) {
                            failed.set(true);
                            errors.add("writer " + writerId + " result " + result.status() + " " + result.reasons());
                        }
                    }
                } catch (Exception e) {
                    failed.set(true);
                    errors.add("writer exception " + e);
                } finally {
                    finishedWriters.countDown();
                }
            });
        }

        for (int reader = 0; reader < 2; reader++) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (finishedWriters.getCount() > 0) {
                        FeatureValue value = store.read("reader", "atomic", "v1", 200_000);
                        if (value.status() != ReadStatus.FOUND
                                && value.status() != ReadStatus.NO_DATA
                                && value.status() != ReadStatus.STALE) {
                            failed.set(true);
                        }
                        PollResult poll = store.poll("reader", 0, 500);
                        for (StreamEvent event : poll.events()) {
                            if (event.offset() >= poll.highWatermark()
                                    || event.write().eventTimeEpochMillis() > 200_000) {
                                failed.set(true);
                                errors.add("bad event " + event.offset() + "/" + poll.highWatermark()
                                        + " eventTime=" + event.write().eventTimeEpochMillis());
                            }
                        }
                        if (store.recoveryReport().recoveredCommitCount() < 0) {
                            failed.set(true);
                        }
                    }
                } catch (Exception e) {
                    failed.set(true);
                    errors.add("reader exception " + e);
                }
            });
        }

        start.countDown();
        assertTrue(finishedWriters.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(false, failed.get(), String.join("; ", errors));
        PollResult all = store.poll("verifier", 0, 1000);
        assertEquals(writers * commitsPerWriter * 2L, all.highWatermark());
        assertEquals(writers * commitsPerWriter * 2, all.events().size());
    }


    @Test
    void exactDuplicatesInsideOneCommitAreIdempotentButConflictsAreRejected() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "repeat", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);
        CommitResult repeated = store.commit(new MaterializationCommit(
                "same-batch-repeat", 5_000,
                List.of(
                        write("repeat", "v1", 1000, "7", 0),
                        write("repeat", "v1", 1000, "7", 0))));
        assertEquals(CommitStatus.APPLIED, repeated.status());
        assertTrue(repeated.reasons().get(0).contains("ignoredExactDuplicateWrites=1"));
        assertEquals("7", store.read("online", "repeat", null, 1000).valueJson());

        CommitResult conflict = store.commit(new MaterializationCommit(
                "same-batch-conflict", 5_001,
                List.of(
                        write("repeat", "v1", 2000, "8", 0),
                        write("repeat", "v1", 2000, "9", 0))));
        assertEquals(CommitStatus.REJECTED, conflict.status());
        assertEquals("7", store.read("online", "repeat", null, 2000).valueJson());
    }


    @Test
    void identicalRegistrationAndRetirementAreIdempotent() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        VersionRegistration registration = new VersionRegistration(
                "stable", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, 10_000, 1000);
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(registration).status());
        assertEquals(RegistrationStatus.ALREADY_REGISTERED, store.registerVersion(registration).status());
        assertEquals(RegistrationStatus.RETIRED, store.retireVersion("stable", "v1", 2000).status());
        assertEquals(RegistrationStatus.ALREADY_RETIRED, store.retireVersion("stable", "v1", 2000).status());
    }


    @Test
    void acceptLatePolicyKeepsVeryLateOutOfOrderWrite() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        assertEquals(RegistrationStatus.REGISTERED, store.registerVersion(new VersionRegistration(
                "lateAccepted", "v1", 1, 60_000, FreshnessStrategy.MARK_STALE,
                LateWriteStrategy.ACCEPT, 100, 100_000)).status());
        store.commit(new MaterializationCommit("high-watermark", 5_000,
                List.of(write("lateAccepted", "v1", 1000, "2", 0))));
        CommitResult late = store.commit(new MaterializationCommit(
                "very-late", 5_001, List.of(write("lateAccepted", "v1", 500, "1", 0))));
        assertEquals(CommitStatus.APPLIED, late.status());
        assertEquals("1", store.read("online", "lateAccepted", null, 500).valueJson());
        assertEquals("2", store.read("online", "lateAccepted", null, 1000).valueJson());
    }


    @Test
    void duplicateCommitIdIgnoresRetryCommitTimeButRejectsDifferentWrites() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "idempotent", "v1", 1000, 60_000, FreshnessStrategy.MARK_STALE);
        FeatureWrite write = write("idempotent", "v1", 1000, "1", 0);
        assertEquals(CommitStatus.APPLIED, store.commit(new MaterializationCommit(
                "retry-commit", 5000, List.of(write))).status());
        assertEquals(CommitStatus.DUPLICATE, store.commit(new MaterializationCommit(
                "retry-commit", 5999, List.of(write))).status());
        assertEquals(CommitStatus.REJECTED, store.commit(new MaterializationCommit(
                "retry-commit", 6000,
                List.of(write, write("idempotent", "v1", 2000, "2", 0)))).status());
    }


    @Test
    void recoveryProducesSamePointResultRegardlessOfCommitRevisionOrderInLog() {
        DurableFeatureStore store = new DurableFeatureStore(directory.toString());
        register(store, "ordered", "v1", 1, 60_000, FreshnessStrategy.MARK_STALE);
        store.commit(new MaterializationCommit("revision-one", 5000,
                List.of(write("ordered", "v1", 1000, "1", 1))));
        store.commit(new MaterializationCommit("revision-zero-late", 5001,
                List.of(write("ordered", "v1", 1000, "0", 0))));

        DurableFeatureStore restarted = new DurableFeatureStore(directory.toString());
        assertEquals("1", restarted.read("online", "ordered", null, 1000).valueJson());
    }

    private static void register(DurableFeatureStore store, String name, String version,
                                 long effectiveFrom, long ttl, FreshnessStrategy strategy) {
        RegistrationResult result = store.registerVersion(new VersionRegistration(
                name, version, effectiveFrom, ttl, strategy,
                LateWriteStrategy.ACCEPT, 10_000, 100_000));
        assertEquals(RegistrationStatus.REGISTERED, result.status(), result.message());
    }

    private static FeatureWrite write(String name, String version, long eventTime,
                                      String value, long revision) {
        return new FeatureWrite(name, version, eventTime, value, revision);
    }
}

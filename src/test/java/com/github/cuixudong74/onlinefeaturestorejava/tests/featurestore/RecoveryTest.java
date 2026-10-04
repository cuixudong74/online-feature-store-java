package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.materialization.DefaultMaterializer;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import com.github.cuixudong74.onlinefeaturestorejava.store.CheckpointStore;
import com.github.cuixudong74.onlinefeaturestorejava.store.InMemoryCheckpointStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 中断恢复：从最近已提交位置继续，不重复应用（效果上）也不跳过；重启后已提交与未完成可区分。 */
class RecoveryTest {
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    /** A checkpoint store that "crashes" (throws) on the n-th commit, after the batch was applied. */
    static class CrashingCheckpointStore extends InMemoryCheckpointStore {
        private final int crashOnCommit;
        private int commits = 0;

        CrashingCheckpointStore(int crashOnCommit) {
            this.crashOnCommit = crashOnCommit;
        }

        @Override
        public void commit(String consumerId, long offset) {
            commits++;
            if (commits == crashOnCommit) {
                throw new RuntimeException("simulated crash after apply, before commit");
            }
            super.commit(consumerId, offset);
        }
    }

    private List<FeatureValue> batch(int fromSeq, int toSeq) {
        List<FeatureValue> out = new ArrayList<>();
        for (int i = fromSeq; i <= toSeq; i++) {
            out.add(TestSupport.val("e1", "f", 1, BASE.plusSeconds(i), "v" + i, i));
        }
        return out;
    }

    @Test
    void resumesFromLastCommittedOffsetWithoutSkipping() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));

        // 10 entries, batch size 3 in TestSupport is 100; use explicit small-batch materializer.
        s.log.append(batch(1, 10));
        DefaultMaterializer m = new DefaultMaterializer(s.log, s.store, s.checkpoints, 4);
        m.materializeOnce();
        assertEquals(10, m.committedOffset());
        assertEquals(Optional.of(10L), s.checkpoints.load(DefaultMaterializer.CONSUMER_ID));

        // 5 more entries arrive, then the process "restarts" (new materializer, same stores).
        s.log.append(batch(11, 15));
        DefaultMaterializer restarted = new DefaultMaterializer(s.log, s.store, s.checkpoints, 4);
        assertEquals(10, restarted.committedOffset(), "must resume from committed offset");
        restarted.materializeOnce();
        assertEquals(15, restarted.committedOffset());

        // Nothing skipped: every value is present.
        for (int i = 1; i <= 15; i++) {
            Object v = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)),
                    BASE.plusSeconds(i)).getRows().get(0).getValues().get("v");
            assertEquals("v" + i, v);
        }
    }

    @Test
    void crashBetweenApplyAndCommitReappliesIdempotently() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        CrashingCheckpointStore crashing = new CrashingCheckpointStore(2);
        s.log.append(batch(1, 10));

        // batch size 4: first commit (offset 4) ok, second batch applied then crash on commit.
        DefaultMaterializer m = new DefaultMaterializer(s.log, s.store, crashing, 4);
        assertThrows(RuntimeException.class, m::materializeOnce);
        assertEquals(Optional.of(4L), crashing.load(DefaultMaterializer.CONSUMER_ID),
                "only the fully committed batch is checkpointed");

        // Recover with a healthy checkpoint store view of the same committed state.
        DefaultMaterializer restarted = new DefaultMaterializer(s.log, s.store, crashing, 4);
        restarted.materializeOnce();
        assertEquals(10, restarted.committedOffset());

        // Reference: same writes applied with no crash. Final states must match exactly
        // (re-application of entries 5-8 had no duplicate effect).
        TestSupport.Stack ref = new TestSupport.Stack();
        ref.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        ref.log.append(batch(1, 10));
        new DefaultMaterializer(ref.log, ref.store, ref.checkpoints, 4).materializeOnce();

        for (int i = 1; i <= 10; i++) {
            Instant asOf = BASE.plusSeconds(i);
            Object crashed = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), asOf)
                    .getRows().get(0).getValues().get("v");
            Object clean = ref.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), asOf)
                    .getRows().get(0).getValues().get("v");
            assertEquals(clean, crashed);
        }
    }

    @Test
    void committedAndUncommittedStateAreDistinguishableAfterRestart() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.log.append(batch(1, 6));
        DefaultMaterializer m = new DefaultMaterializer(s.log, s.store, s.checkpoints, 4);
        m.materializeOnce();

        // Committed: 6. Now append more entries but do not materialize (uncommitted).
        s.log.append(batch(7, 9));

        // "Restart": a fresh materializer sees only the committed position.
        DefaultMaterializer restarted = new DefaultMaterializer(s.log, s.store, s.checkpoints, 4);
        assertEquals(6, restarted.committedOffset());
        Map<String, Long> all = s.checkpoints.loadAll();
        assertEquals(6L, all.get(DefaultMaterializer.CONSUMER_ID));
        assertEquals(9, s.log.nextOffset(), "log retains uncommitted entries for later application");

        restarted.materializeOnce();
        assertEquals(9, restarted.committedOffset());
    }

    @Test
    void consumerCheckpointsAreIndependentOfMaterializer() {
        TestSupport.Stack s = new TestSupport.Stack();
        CheckpointStore cp = s.checkpoints;
        cp.commit("consumer-a", 3);
        cp.commit("consumer-b", 7);
        // stale commit must not move a checkpoint backwards
        cp.commit("consumer-a", 1);
        assertEquals(Optional.of(3L), cp.load("consumer-a"));
        assertEquals(Optional.of(7L), cp.load("consumer-b"));
    }
}

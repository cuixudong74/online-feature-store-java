package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 乱序、重复与迟到写入：同一批写入无论顺序如何，最终状态必须一致且幂等。 */
class WriteOrderTest {

    private static final Instant T1 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2024-01-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2024-01-03T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2024-02-01T00:00:00Z");

    private List<FeatureValue> writes() {
        return List.of(
                TestSupport.val("e1", "f", 1, T1, "a", 1),
                TestSupport.val("e1", "f", 1, T2, "b", 2),
                TestSupport.val("e1", "f", 1, T3, "c", 3));
    }

    private Object readLatest(TestSupport.Stack s) {
        return s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), AS_OF)
                .getRows().get(0).getValues().get("v");
    }

    @Test
    void outOfOrderWritesConvergeToSameState() {
        TestSupport.Stack forward = new TestSupport.Stack();
        forward.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        forward.service.writeFeatures(writes());

        TestSupport.Stack reverse = new TestSupport.Stack();
        reverse.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        List<FeatureValue> reversed = new ArrayList<>(writes());
        Collections.reverse(reversed);
        reverse.service.writeFeatures(reversed);

        assertEquals(readLatest(forward), readLatest(reverse));
        assertEquals("c", readLatest(reverse));
    }

    @Test
    void duplicateWritesAreIdempotent() {
        TestSupport.Stack once = new TestSupport.Stack();
        once.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        once.service.writeFeatures(writes());

        TestSupport.Stack twice = new TestSupport.Stack();
        twice.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        twice.service.writeFeatures(writes());
        twice.service.writeFeatures(writes()); // exact duplicates
        twice.service.writeFeatures(List.of(writes().get(1))); // partial duplicate

        assertEquals(readLatest(once), readLatest(twice));
    }

    @Test
    void sameTimestampResolvedDeterministicallyBySeq() {
        // Two stacks apply the same conflicting writes at T1 in opposite order.
        TestSupport.Stack s1 = new TestSupport.Stack();
        s1.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s1.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, T1, "low-seq", 1),
                TestSupport.val("e1", "f", 1, T1, "high-seq", 2)));

        TestSupport.Stack s2 = new TestSupport.Stack();
        s2.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s2.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, T1, "high-seq", 2),
                TestSupport.val("e1", "f", 1, T1, "low-seq", 1)));

        assertEquals("high-seq", readLatest(s1));
        assertEquals(readLatest(s1), readLatest(s2));
    }

    @Test
    void lateWriteIsAcceptedAndVisibleOnlyToCoveringReads() {
        // 既定策略：迟到写入（eventTime 早于当前最新）被接受并按 eventTime 归位，
        // 不篡改更早 asOf 的历史读取结果。
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T3, "newest", 3)));

        // late write for T1 arrives after T3 was committed
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T1, "late", 1)));

        // read covering T1 but not T3 sees the late value
        assertEquals("late", s.service
                .getFeatures("e1", List.of(new FeatureRequest("f", null)),
                        Instant.parse("2024-01-01T12:00:00Z"))
                .getRows().get(0).getValues().get("v"));
        // read at AS_OF still sees the newest
        assertEquals("newest", readLatest(s));
    }
}

package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureRow;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新鲜度语义：eventTime + ttl &ge; asOf 为新鲜（边界上仍新鲜）；
 * eventTime + ttl &lt; asOf 为过期，按 StalenessPolicy 处理。
 */
class StalenessTest {
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant T = Instant.parse("2024-01-01T00:00:00Z");

    private FeatureRow read(TestSupport.Stack s, String feature, Instant asOf) {
        return s.service.getFeatures("e1", List.of(new FeatureRequest(feature, null)), asOf)
                .getRows().get(0);
    }

    @Test
    void boundaryAtExactlyTtlIsStillFresh() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, TTL, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "x", 1)));

        assertFalse(read(s, "f", T.plus(TTL)).isStale(), "exactly at TTL must still be fresh");
        assertTrue(read(s, "f", T.plus(TTL).plusNanos(1)).isStale(), "past TTL must be stale");
    }

    @Test
    void markStaleReturnsValueFlaggedStale() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, TTL, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "x", 1)));

        FeatureRow row = read(s, "f", T.plus(Duration.ofHours(2)));
        assertFalse(row.isMissing());
        assertTrue(row.isStale());
        assertEquals("x", row.getValues().get("v"));
    }

    @Test
    void failPolicyTreatsStaleAsMissing() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, TTL, StalenessPolicy.FAIL));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "x", 1)));

        assertTrue(read(s, "f", T.plus(Duration.ofHours(2))).isMissing());
        assertFalse(read(s, "f", T.plus(Duration.ofMinutes(30))).isMissing());
    }

    @Test
    void fallbackReturnsPreviousUsableVersion() {
        // v2 的最新值已过期，v1 的值在 asOf 仍新鲜 -> 回退到 v1。
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, TTL, StalenessPolicy.FALLBACK_TO_PREVIOUS));
        s.service.registerFeature(TestSupport.def("f", 2, TTL, StalenessPolicy.FALLBACK_TO_PREVIOUS));
        Instant asOf = T.plus(Duration.ofMinutes(75));
        s.service.writeFeatures(List.of(
                // v1 updated recently: fresh at asOf (30 + 60 >= 75)
                TestSupport.val("e1", "f", 1, T.plus(Duration.ofMinutes(30)), "v1-fresh", 1),
                // v2 backfilled long ago: stale at asOf (0 + 60 < 75)
                TestSupport.val("e1", "f", 2, T, "v2-stale", 2)));

        FeatureRow row = read(s, "f", asOf);
        assertFalse(row.isMissing());
        assertFalse(row.isStale());
        assertEquals(1, row.getFeatureVersion(), "must fall back to previous usable version");
        assertEquals("v1-fresh", row.getValues().get("v"));
    }

    @Test
    void fallbackReturnsMissingWhenNoUsableVersion() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, TTL, StalenessPolicy.FALLBACK_TO_PREVIOUS));
        s.service.registerFeature(TestSupport.def("f", 2, TTL, StalenessPolicy.FALLBACK_TO_PREVIOUS));
        s.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, T, "v1-stale", 1),
                TestSupport.val("e1", "f", 2, T, "v2-stale", 2)));

        assertTrue(read(s, "f", T.plus(Duration.ofHours(3))).isMissing(),
                "all versions expired -> missing");
    }

    @Test
    void nullTtlMeansNeverExpires() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.FAIL));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "x", 1)));
        FeatureRow row = read(s, "f", T.plus(Duration.ofDays(3650)));
        assertFalse(row.isMissing());
        assertFalse(row.isStale());
    }
}

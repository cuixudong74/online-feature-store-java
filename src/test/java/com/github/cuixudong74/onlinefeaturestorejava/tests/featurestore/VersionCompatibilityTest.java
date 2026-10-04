package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureRow;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 版本兼容：新增/下线版本时已有读取请求不得报错；旧版本在保留期内可读，过期后按缺失处理。 */
class VersionCompatibilityTest {
    private static final Instant T = Instant.parse("2024-01-01T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant t) { this.now = t; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private TestSupport.Stack stackWithClock(MutableClock clock) {
        TestSupport.Stack s = new TestSupport.Stack();
        s.clock = clock;
        s.service = s.newService();
        return s;
    }

    @Test
    void existingReadsKeepWorkingWhenNewVersionIsAdded() {
        MutableClock clock = new MutableClock(T);
        TestSupport.Stack s = stackWithClock(clock);
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "v1-val", 1)));

        // new version appears; unversioned requests switch to it, explicit v1 requests still work
        s.service.registerFeature(TestSupport.def("f", 2, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 2, T, "v2-val", 2)));

        FeatureRow unversioned = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), T)
                .getRows().get(0);
        assertEquals("v2-val", unversioned.getValues().get("v"));
        assertEquals(2, unversioned.getFeatureVersion());

        FeatureRow explicitV1 = s.service.getFeatures("e1", List.of(new FeatureRequest("f", 1)), T)
                .getRows().get(0);
        assertEquals("v1-val", explicitV1.getValues().get("v"));
    }

    @Test
    void deprecatedVersionReadableDuringRetentionThenMissing() {
        MutableClock clock = new MutableClock(T);
        TestSupport.Stack s = stackWithClock(clock);
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, T, "v1-val", 1)));

        Instant deprecatedAt = T.plus(Duration.ofDays(1));
        s.service.deprecateFeature("f", 1, deprecatedAt);

        // during retention: still readable, no error
        clock.set(deprecatedAt.plus(Duration.ofDays(3)));
        FeatureRow during = s.service.getFeatures("e1", List.of(new FeatureRequest("f", 1)), T)
                .getRows().get(0);
        assertFalse(during.isMissing());
        assertEquals("v1-val", during.getValues().get("v"));

        // after retention: reads as missing, still no error
        clock.set(deprecatedAt.plus(RETENTION).plus(Duration.ofDays(1)));
        FeatureRow after = s.service.getFeatures("e1", List.of(new FeatureRequest("f", 1)), T)
                .getRows().get(0);
        assertTrue(after.isMissing());
    }

    @Test
    void unversionedRequestFallsBackToRemainingActiveVersionAfterDeprecation() {
        MutableClock clock = new MutableClock(T);
        TestSupport.Stack s = stackWithClock(clock);
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s.service.registerFeature(TestSupport.def("f", 2, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, T, "v1-val", 1),
                TestSupport.val("e1", "f", 2, T, "v2-val", 2)));

        // take v2 offline: unversioned reads must not error, they resolve to v1
        s.service.deprecateFeature("f", 2, T.plus(Duration.ofDays(1)));
        FeatureRow row = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), T)
                .getRows().get(0);
        assertFalse(row.isMissing());
        assertEquals(1, row.getFeatureVersion());
        assertEquals("v1-val", row.getValues().get("v"));
    }

    @Test
    void unknownFeatureReadsAsMissingNotError() {
        TestSupport.Stack s = new TestSupport.Stack();
        FeatureRow row = s.service.getFeatures("e1", List.of(new FeatureRequest("nope", null)), T)
                .getRows().get(0);
        assertTrue(row.isMissing());
        FeatureRow rowV = s.service.getFeatures("e1", List.of(new FeatureRequest("nope", 3)), T)
                .getRows().get(0);
        assertTrue(rowV.isMissing());
    }
}

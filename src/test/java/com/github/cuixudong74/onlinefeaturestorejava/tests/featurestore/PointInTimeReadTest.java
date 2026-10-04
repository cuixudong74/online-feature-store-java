package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureRow;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureVector;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 特征穿越：任何情况下都不得读到时间点之后才产生的特征。 */
class PointInTimeReadTest {

    @Test
    void neverReadsValuesProducedAfterAsOf() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));

        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2024-01-02T00:00:00Z");
        Instant t3 = Instant.parse("2024-01-03T00:00:00Z");
        s.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, t1, "v1", 1),
                TestSupport.val("e1", "f", 1, t2, "v2", 2),
                TestSupport.val("e1", "f", 1, t3, "v3", 3)));

        // asOf exactly at t2 -> v2, not v3
        FeatureVector at = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), t2);
        assertEquals("v2", at.getRows().get(0).getValues().get("v"));

        // asOf between t1 and t2 -> v1
        FeatureVector between = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)),
                Instant.parse("2024-01-01T12:00:00Z"));
        assertEquals("v1", between.getRows().get(0).getValues().get("v"));

        // asOf before any write -> missing, never a future value
        FeatureVector before = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)),
                Instant.parse("2023-12-31T00:00:00Z"));
        assertTrue(before.getRows().get(0).isMissing());
    }

    @Test
    void futureWriteDoesNotChangeEarlierPointInTimeReads() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant asOf = Instant.parse("2024-01-05T00:00:00Z");

        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, t1, "old", 1)));
        FeatureRow before = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), asOf)
                .getRows().get(0);

        // a later write with eventTime after asOf must not affect the earlier read
        s.service.writeFeatures(List.of(
                TestSupport.val("e1", "f", 1, Instant.parse("2024-01-10T00:00:00Z"), "future", 2)));
        FeatureRow after = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)), asOf)
                .getRows().get(0);

        assertEquals(before.getValues(), after.getValues());
        assertEquals(before.getEventTime(), after.getEventTime());
    }

    @Test
    void readsAreScopedPerEntityAndVersion() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        Instant t = Instant.parse("2024-01-01T00:00:00Z");
        s.service.writeFeatures(List.of(TestSupport.val("e1", "f", 1, t, "for-e1", 1)));

        FeatureVector other = s.service.getFeatures("e2", List.of(new FeatureRequest("f", null)), t);
        assertTrue(other.getRows().get(0).isMissing());

        FeatureVector wrongVersion = s.service.getFeatures("e1", List.of(new FeatureRequest("f", 99)), t);
        assertTrue(wrongVersion.getRows().get(0).isMissing());
    }
}

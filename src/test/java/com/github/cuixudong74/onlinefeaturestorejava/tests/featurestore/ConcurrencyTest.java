package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.api.FeatureService.FeatureRequest;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureRow;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 并发读写：读到的必须是某个已提交的完整状态，不得出现半更新或字段级混合。 */
class ConcurrencyTest {

    @Test
    void readersNeverObservePartiallyAppliedBatches() throws Exception {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        int generations = 200;

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean violation = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        // Writer: each generation writes two fields atomically (a == b == g).
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int g = 1; g <= generations; g++) {
                    FeatureValue v = new FeatureValue("e1", "f", 1, base.plusSeconds(g),
                            Map.of("a", g, "b", g), g, "w");
                    s.service.writeFeatures(List.of(v));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stop.set(true);
            }
        });

        // Readers: any observed row must have a == b (no field-level mixing).
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        FeatureRow row = s.service.getFeatures("e1",
                                List.of(new FeatureRequest("f", null)),
                                base.plusSeconds(generations + 1000)).getRows().get(0);
                        if (!row.isMissing() && !row.getValues().get("a").equals(row.getValues().get("b"))) {
                            violation.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> f : readers) f.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertFalse(violation.get(), "observed a row with mixed fields from different generations");
        FeatureRow last = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)),
                base.plusSeconds(generations + 1000)).getRows().get(0);
        assertEquals(generations, last.getValues().get("a"));
    }

    @Test
    void concurrentWritersConverge() throws Exception {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        int threads = 4, perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tId = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    int seq = tId * perThread + i + 1;
                    s.service.writeFeatures(List.of(new FeatureValue("e1", "f", 1,
                            base.plusSeconds(seq), Map.of("v", seq), seq, "w" + tId)));
                }
            }));
        }
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // All 200 writes applied exactly once in effect; latest seq wins at the end.
        FeatureRow row = s.service.getFeatures("e1", List.of(new FeatureRequest("f", null)),
                base.plusSeconds(threads * perThread + 1000)).getRows().get(0);
        assertEquals(threads * perThread, row.getValues().get("v"));
        assertEquals(threads * perThread, s.materializer.committedOffset());
    }
}

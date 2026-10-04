package com.github.cuixudong74.onlinefeaturestorejava.tests.featurestore;

import com.github.cuixudong74.onlinefeaturestorejava.materialization.DefaultLogConsumer;
import com.github.cuixudong74.onlinefeaturestorejava.materialization.LogConsumer;
import com.github.cuixudong74.onlinefeaturestorejava.model.FeatureValue;
import com.github.cuixudong74.onlinefeaturestorejava.model.StalenessPolicy;
import com.github.cuixudong74.onlinefeaturestorejava.store.FeatureLog.LogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 多消费方：各自进度独立，慢消费方不阻塞快消费方，也不拖住写入链路。 */
class MultiConsumerTest {
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    private List<FeatureValue> batch(int from, int to) {
        List<FeatureValue> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(TestSupport.val("e1", "f", 1, BASE.plusSeconds(i), "v" + i, i));
        }
        return out;
    }

    @Test
    void consumersProgressIndependently() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        s.service.writeFeatures(batch(1, 10));

        List<FeatureValue> fastSeen = new CopyOnWriteArrayList<>();
        List<FeatureValue> slowSeen = new CopyOnWriteArrayList<>();
        LogConsumer fast = new DefaultLogConsumer("fast", s.log, s.checkpoints,
                entries -> entries.forEach(e -> fastSeen.add(e.value())));
        LogConsumer slow = new DefaultLogConsumer("slow", s.log, s.checkpoints,
                entries -> entries.forEach(e -> slowSeen.add(e.value())));

        // fast consumer drains everything; slow consumer only polls a little
        fast.poll(100);
        slow.poll(3);

        assertEquals(10, fast.committedOffset());
        assertEquals(3, slow.committedOffset());
        assertEquals(10, fastSeen.size());
        assertEquals(3, slowSeen.size());

        // more writes are not held back by the slow consumer
        s.service.writeFeatures(batch(11, 15));
        assertEquals(15, s.log.nextOffset());
        fast.poll(100);
        assertEquals(15, fast.committedOffset());

        // slow consumer later catches up from its own position, seeing every entry once
        slow.poll(100);
        assertEquals(15, slow.committedOffset());
        assertEquals(15, slowSeen.size());
        assertEquals(fastSeen, slowSeen, "both consumers observe the same ordered stream");
    }

    @Test
    void consumerResumesFromOwnCheckpointAfterRestart() {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.writeFeatures(batch(1, 5));
        LogConsumer c1 = new DefaultLogConsumer("c", s.log, s.checkpoints, b -> {});
        c1.poll(3);
        assertEquals(3, c1.committedOffset());

        // "restart" the consumer: new instance, same id and stores
        List<FeatureValue> seen = new ArrayList<>();
        LogConsumer c2 = new DefaultLogConsumer("c", s.log, s.checkpoints,
                entries -> entries.forEach(e -> seen.add(e.value())));
        assertEquals(3, c2.committedOffset());
        c2.poll(100);
        assertEquals(5, c2.committedOffset());
        assertEquals(2, seen.size(), "only entries after the checkpoint are delivered");
    }

    @Test
    void slowConsumerDoesNotBlockFastConsumerOrWriter() throws Exception {
        TestSupport.Stack s = new TestSupport.Stack();
        s.service.registerFeature(TestSupport.def("f", 1, null, StalenessPolicy.MARK_STALE));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);

        // slow consumer blocks inside its handler
        Future<?> slow = pool.submit(() -> {
            LogConsumer c = new DefaultLogConsumer("slow", s.log, s.checkpoints, b -> {
                slowStarted.countDown();
                try {
                    slowRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            c.poll(1);
        });
        slowStarted.await(5, TimeUnit.SECONDS);

        // while the slow consumer is stuck, writes and the fast consumer proceed
        Future<?> writer = pool.submit(() -> s.service.writeFeatures(batch(1, 20)));
        Future<Long> fast = pool.submit(() -> {
            LogConsumer c = new DefaultLogConsumer("fast", s.log, s.checkpoints, b -> {});
            c.poll(100);
            return c.committedOffset();
        });
        writer.get(5, TimeUnit.SECONDS);
        assertEquals(20, fast.get(5, TimeUnit.SECONDS),
                "fast consumer must not be blocked by the slow one");
        assertEquals(20, s.log.nextOffset(), "write path must not be blocked by the slow consumer");

        slowRelease.countDown();
        slow.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();
    }
}

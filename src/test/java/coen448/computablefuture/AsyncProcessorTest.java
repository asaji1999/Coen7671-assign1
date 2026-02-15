package coen448.computablefuture.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;


public class AsyncProcessorTest {

    

    
    static class ImmediateMicroservice extends Microservice {
        private final String prefix;

        ImmediateMicroservice(String serviceId) {
            super(serviceId);
            this.prefix = serviceId;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String input) {
            return CompletableFuture.completedFuture(prefix + ":" + input);
        }
    }

    /** Always completes exceptionally. */
    static class FailingMicroservice extends Microservice {
        FailingMicroservice(String serviceId) {
            super(serviceId);
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String input) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("boom-" + input));
            return f;
        }
    }

    /** Completes after a fixed delay. */
    static class DelayedMicroservice extends Microservice {
        private final String prefix;
        private final long delayMs;

        DelayedMicroservice(String serviceId, long delayMs) {
            super(serviceId);
            this.prefix = serviceId;
            this.delayMs = delayMs;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String input) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return prefix + ":" + input;
            });
        }
    }

    
    static class AlternatingDelayMicroservice extends Microservice {
        private final String prefix;
        private final AtomicInteger runCounter;
        private final long evenDelayMs;
        private final long oddDelayMs;

        AlternatingDelayMicroservice(String serviceId, AtomicInteger runCounter, long evenDelayMs, long oddDelayMs) {
            super(serviceId);
            this.prefix = serviceId;
            this.runCounter = runCounter;
            this.evenDelayMs = evenDelayMs;
            this.oddDelayMs = oddDelayMs;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String input) {
            int run = runCounter.get();
            long delay = (run % 2 == 0) ? evenDelayMs : oddDelayMs;
            return CompletableFuture.supplyAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return prefix + ":" + input;
            });
        }
    }

    

    @Test
    void processAsync_allSuccess_joinsInServiceOrder() throws Exception {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(
                new ImmediateMicroservice("A"),
                new ImmediateMicroservice("B"),
                new ImmediateMicroservice("C")
        );

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String out = p.processAsync(services, "MSG").get(500, TimeUnit.MILLISECONDS);
            assertEquals("A:MSG B:MSG C:MSG", out);
        });
    }

    @Test
    void processAsyncCompletionOrder_doesNotAssumeFixedOrder() throws Exception {
        AsyncProcessor p = new AsyncProcessor();
        AtomicInteger runCounter = new AtomicInteger(0);

        Microservice A = new AlternatingDelayMicroservice("A", runCounter, 30, 0);
        Microservice B = new AlternatingDelayMicroservice("B", runCounter, 0, 30);

        List<Microservice> services = Arrays.asList(A, B);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            runCounter.set(0);
            List<String> order1 = p.processAsyncCompletionOrder(services, "MSG").get(800, TimeUnit.MILLISECONDS);

            runCounter.set(1);
            List<String> order2 = p.processAsyncCompletionOrder(services, "MSG").get(800, TimeUnit.MILLISECONDS);

            // Don't assert an exact order; just assert contents and size.
            assertEquals(2, order1.size());
            assertTrue(order1.contains("A:MSG"));
            assertTrue(order1.contains("B:MSG"));

            assertEquals(2, order2.size());
            assertTrue(order2.contains("A:MSG"));
            assertTrue(order2.contains("B:MSG"));

            // Demonstrate completion order can vary.
            List<String> ab = List.of("A:MSG", "B:MSG");
            List<String> ba = List.of("B:MSG", "A:MSG");
            assertTrue(order1.equals(ab) || order1.equals(ba), "order1 must be AB or BA");
            assertTrue(order2.equals(ab) || order2.equals(ba), "order2 must be AB or BA");
        });
    }

    // ---------- Required failure policy tests ----------

    @Test
    void failFast_failsIfAnyServiceFails() {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(
                new ImmediateMicroservice("A"),
                new FailingMicroservice("B"),
                new ImmediateMicroservice("C")
        );
        List<String> msgs = Arrays.asList("m1", "m2", "m3");

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            CompletableFuture<String> f = p.processAsyncFailFast(services, msgs);
            // use get(timeout) to satisfy liveness rule and assert exceptional completion
            assertThrows(ExecutionException.class, () -> f.get(500, TimeUnit.MILLISECONDS));
        });
    }

    @Test
    void failPartial_returnsOnlySuccessfulResults_andDoesNotThrow() throws Exception {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(
                new ImmediateMicroservice("A"),
                new FailingMicroservice("B"),
                new ImmediateMicroservice("C")
        );
        List<String> msgs = Arrays.asList("m1", "m2", "m3");

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            List<String> out = p.processAsyncFailPartial(services, msgs).get(500, TimeUnit.MILLISECONDS);
            assertEquals(2, out.size());
            assertTrue(out.contains("A:m1"));
            assertTrue(out.contains("C:m3"));
        });
    }

    @Test
    void failSoft_replacesFailuresWithFallback_andCompletesNormally() throws Exception {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(
                new ImmediateMicroservice("A"),
                new FailingMicroservice("B"),
                new ImmediateMicroservice("C")
        );
        List<String> msgs = Arrays.asList("m1", "m2", "m3");

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String out = p.processAsyncFailSoft(services, msgs, "FALLBACK").get(500, TimeUnit.MILLISECONDS);
            // preserve service/message pair order in join output
            assertEquals("A:m1 FALLBACK C:m3", out);
        });
    }

    // ---------- Liveness / timeout-specific test ----------

    @Test
    void liveness_asyncOperationsCompleteWithinTimeout() throws Exception {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(
                new DelayedMicroservice("A", 50),
                new DelayedMicroservice("B", 20),
                new DelayedMicroservice("C", 10)
        );

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String out = p.processAsync(services, "MSG").get(1500, TimeUnit.MILLISECONDS);
            // don't assert completion order (this method preserves service list order via join)
            assertEquals("A:MSG B:MSG C:MSG", out);
        });
    }

    // ---------- Input validation tests ----------

    @Test
    void failFast_throwsOnMismatchedListSizes() {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(new ImmediateMicroservice("A"));
        List<String> msgs = Arrays.asList("m1", "m2");

        assertThrows(IllegalArgumentException.class, () -> p.processAsyncFailFast(services, msgs));
    }

    @Test
    void failPartial_throwsOnMismatchedListSizes() {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(new ImmediateMicroservice("A"));
        List<String> msgs = Arrays.asList("m1", "m2");

        assertThrows(IllegalArgumentException.class, () -> p.processAsyncFailPartial(services, msgs));
    }

    @Test
    void failSoft_throwsOnMismatchedListSizes() {
        AsyncProcessor p = new AsyncProcessor();
        List<Microservice> services = Arrays.asList(new ImmediateMicroservice("A"));
        List<String> msgs = Arrays.asList("m1", "m2");

        assertThrows(IllegalArgumentException.class, () -> p.processAsyncFailSoft(services, msgs, "FALLBACK"));
    }
}

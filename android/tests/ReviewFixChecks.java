package com.pilipala.marcomovie;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic regressions for initialization ownership and episode identity. */
public final class ReviewFixChecks {
    static int checks;

    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    static void waiting(Thread t) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < end) Thread.sleep(1);
        check(t.getState() == Thread.State.WAITING, "waiter joined shared initialization");
    }

    public static void main(String[] args) throws Exception {
        SingleFlight<Object> flight = new SingleFlight<>();
        Object result = new Object();
        AtomicInteger initialized = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Object> owner =
                    executor.submit(
                            () ->
                                    flight.get(
                                            "epoch0:site",
                                            () -> {
                                                initialized.incrementAndGet();
                                                entered.countDown();
                                                release.await();
                                                return result;
                                            }));
            check(entered.await(2, TimeUnit.SECONDS), "owner initialization started");
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread cancelled =
                    new Thread(
                            () -> {
                                try {
                                    flight.get(
                                            "epoch0:site",
                                            () -> {
                                                initialized.incrementAndGet();
                                                return new Object();
                                            });
                                    unexpected.set(new AssertionError("cancelled waiter returned"));
                                } catch (InterruptedException expected) {
                                    interrupted.set(true);
                                } catch (Throwable error) {
                                    unexpected.set(error);
                                }
                            });
            cancelled.start();
            waiting(cancelled);
            cancelled.interrupt();
            cancelled.join(2000);
            check(
                    !cancelled.isAlive() && interrupted.get() && unexpected.get() == null,
                    "cancelling waiter does not cancel owner");
            AtomicReference<Object> shared = new AtomicReference<>();
            Thread remaining =
                    new Thread(
                            () -> {
                                try {
                                    shared.set(
                                            flight.get(
                                                    "epoch0:site",
                                                    () -> {
                                                        initialized.incrementAndGet();
                                                        return new Object();
                                                    }));
                                } catch (Throwable error) {
                                    unexpected.set(error);
                                }
                            });
            remaining.start();
            waiting(remaining);
            Future<Object> other = executor.submit(() -> flight.get("epoch1:site", Object::new));
            check(
                    other.get(2, TimeUnit.SECONDS) != result,
                    "new configuration initializes independently");
            release.countDown();
            check(owner.get(2, TimeUnit.SECONDS) == result, "owner receives initialized instance");
            remaining.join(2000);
            check(
                    !remaining.isAlive()
                            && shared.get() == result
                            && initialized.get() == 1
                            && unexpected.get() == null,
                    "all callers share one instance after cancelled waiter");
            try {
                flight.get(
                        "failure",
                        () -> {
                            throw new IOException("fixture");
                        });
                throw new AssertionError("missing failure");
            } catch (IOException expected) {
                check(
                        "fixture".equals(expected.getMessage()),
                        "original initialization exception preserved");
            }
            check(flight.get("failure", () -> result) == result, "failed initialization can retry");
            check(
                    flight.get("epoch0:site", () -> result) == result,
                    "completed flight releases pending entry");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        for (int index : new int[] {0, 49, 50, 123, 199}) {
            check(
                    EpisodeSelection.pageFor(index, 200, false) == index / 50,
                    "ascending page for " + index);
            check(
                    EpisodeSelection.pageFor(index, 200, true) == (199 - index) / 50,
                    "descending page for " + index);
        }
        check(EpisodeSelection.pageFor(0, 0, true) == 0, "empty episode list stays on first page");
        check(EpisodeSelection.pageFor(999, 200, false) == 3, "stale episode index bounded");
        EpisodeSelection selection = new EpisodeSelection();
        selection.select("alternate", 2, 200);
        check(
                selection.selected && selection.page == 2 && selection.line.equals("alternate"),
                "browse state retains alternate line and page without playback");
        selection.select("alternate", 9, 51);
        check(selection.page == 1, "shortened episode list bounds restored page");
        check(
                ImageDecodePolicy.sample(1000, 1500, 448, 672, false, true) == 2,
                "detail keeps enough pixels instead of decoding a quarter-size poster");
        check(
                ImageDecodePolicy.sample(2000, 3000, 896, 1344, false, true) == 2,
                "4K detail requests adequate artwork pixels");
        check(
                ImageDecodePolicy.sample(1920, 1080, 448, 672, false, true) == 4,
                "landscape artwork uses fitted dimensions");
        check(
                ImageDecodePolicy.sample(200, 300, 448, 672, false, true) == 1,
                "low resolution source is not pretended to contain extra detail");
        int bounded = ImageDecodePolicy.sample(12000, 18000, 1080, 1620, false, true);
        check(
                (long) (12000 / bounded) * (18000 / bounded) <= 2L * 1024 * 1024,
                "very large poster decode remains bounded");
        System.out.println(
                "OK " + checks + " review regression checks; no device performance claim.");
    }
}

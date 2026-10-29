package com.tiktok.appevents;

import static org.junit.Assert.assertEquals;

import com.tiktok.util.TimeUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class OccurrenceTimestampTest {
    @Test
    public void concurrentMonitorAndEventFormattingCannotChangeOriginalOccurrenceTime()
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                final boolean old = index % 2 == 0;
                results.add(
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                    } catch (InterruptedException error) {
                                        throw new AssertionError(error);
                                    }
                                    Date occurredAt = new Date(old ? 0L : 1700000000000L);
                                    String expected =
                                            old ? "1970-01-01T00:00:00Z" : "2023-11-14T22:13:20Z";
                                    for (int attempt = 0; attempt < 1000; attempt++) {
                                        assertEquals(
                                                expected, TimeUtil.getISO8601Timestamp(occurredAt));
                                    }
                                }));
            }
            start.countDown();
            for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }
}

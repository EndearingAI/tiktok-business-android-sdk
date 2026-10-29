package com.tiktok;

import static org.junit.Assert.*;

import com.tiktok.util.TTTrackingConsent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InitializationCallbacksTest {
    @Before
    public void grantConsent() {
        TTTrackingConsent.setEnabled(true);
    }

    @After
    public void withdrawConsent() {
        TTTrackingConsent.setEnabled(false);
    }

    @Test
    public void staleNativeCompletionCannotSettleTheNewGeneration() {
        InitializationCallbacks old = new InitializationCallbacks(TTTrackingConsent.capture());
        Outcome oldResult = new Outcome();
        old.add(oldResult);
        TTTrackingConsent.setEnabled(false);
        TTTrackingConsent.setEnabled(true);
        InitializationCallbacks current = new InitializationCallbacks(TTTrackingConsent.capture());
        Outcome currentResult = new Outcome();
        current.add(currentResult);

        old.success();
        old.fail(-1, "Late old initialization error");
        assertEquals(0, oldResult.successes);
        assertEquals(1, oldResult.failures);
        assertTrue(oldResult.message.contains("consent"));
        assertEquals(0, currentResult.successes);
        assertEquals(0, currentResult.failures);
        current.success();
        assertEquals(1, currentResult.successes);
        assertEquals(0, currentResult.failures);
    }

    @Test
    public void withdrawalInsideACompletionCancelsEveryRemainingCallbackOnce() {
        InitializationCallbacks callbacks =
                new InitializationCallbacks(TTTrackingConsent.capture());
        Outcome completed = new Outcome();
        Outcome cancelled = new Outcome();
        callbacks.add(
                new TikTokBusinessSdk.TTInitCallback() {
                    @Override
                    public void success() {
                        completed.success();
                        TTTrackingConsent.setEnabled(false);
                        callbacks.cancelForConsent();
                    }

                    @Override
                    public void fail(int code, String message) {
                        org.junit.Assert.fail("First initialization should complete");
                    }
                });
        callbacks.add(cancelled);
        callbacks.success();
        callbacks.success();
        callbacks.cancelForConsent();

        assertEquals(1, completed.successes);
        assertEquals(1, cancelled.failures);
        assertEquals(0, cancelled.successes);
        assertTrue(cancelled.message.contains("consent"));
    }

    @Test
    public void aThrowingCallbackCannotAbandonOtherCancellationNotifications() {
        InitializationCallbacks callbacks =
                new InitializationCallbacks(TTTrackingConsent.capture());
        Outcome cancelled = new Outcome();
        callbacks.add(
                new TikTokBusinessSdk.TTInitCallback() {
                    @Override
                    public void success() {
                        org.junit.Assert.fail("Consent was withdrawn");
                    }

                    @Override
                    public void fail(int code, String message) {
                        throw new IllegalStateException("Application callback failed");
                    }
                });
        callbacks.add(cancelled);
        TTTrackingConsent.setEnabled(false);
        assertThrows(IllegalStateException.class, callbacks::cancelForConsent);
        assertEquals(1, cancelled.failures);
        assertTrue(cancelled.message.contains("consent"));
        callbacks.cancelForConsent();
        assertEquals(1, cancelled.failures);
    }

    private static class Outcome implements TikTokBusinessSdk.TTInitCallback {
        int successes;
        int failures;
        String message;

        @Override
        public void success() {
            successes++;
        }

        @Override
        public void fail(int code, String message) {
            failures++;
            this.message = message;
        }
    }
}

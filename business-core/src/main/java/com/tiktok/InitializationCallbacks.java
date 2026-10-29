package com.tiktok;

import com.tiktok.util.TTTrackingConsent;

import java.util.ArrayDeque;

/** Owns exactly-once callback delivery for one SDK initialization generation. */
final class InitializationCallbacks implements TikTokBusinessSdk.TTInitCallback {
    private static final int CONSENT_REQUIRED = -3;
    private static final String CONSENT_REQUIRED_MESSAGE =
            "Tracking consent is required before initializing the TikTok SDK";
    private static final String CONSENT_WITHDRAWN_MESSAGE =
            "Tracking consent was withdrawn before TikTok SDK initialization completed";

    private final long generation;
    private final ArrayDeque<TikTokBusinessSdk.TTInitCallback> pending = new ArrayDeque<>();
    private boolean finished;
    private int resultCode;
    private String resultMessage;

    InitializationCallbacks(long generation) {
        this.generation = generation;
    }

    static void rejectWithoutConsent(TikTokBusinessSdk.TTInitCallback callback) {
        if (callback != null) callback.fail(CONSENT_REQUIRED, CONSENT_REQUIRED_MESSAGE);
    }

    void add(TikTokBusinessSdk.TTInitCallback callback) {
        synchronized (TTTrackingConsent.LOCK) {
            if (callback == null) return;
            if (finished) {
                deliver(callback, resultCode, resultMessage);
            } else {
                pending.addLast(callback);
            }
        }
    }

    @Override
    public void success() {
        complete(0, null);
    }

    @Override
    public void fail(int code, String message) {
        complete(code, message);
    }

    void cancelForConsent() {
        synchronized (TTTrackingConsent.LOCK) {
            if (finished) return;
            finished = true;
            resultCode = CONSENT_REQUIRED;
            resultMessage = CONSENT_WITHDRAWN_MESSAGE;
            RuntimeException failure = null;
            while (!pending.isEmpty()) {
                failure = notifyNext(resultCode, resultMessage, failure);
            }
            if (failure != null) throw failure;
        }
    }

    private void complete(int code, String message) {
        synchronized (TTTrackingConsent.LOCK) {
            if (finished) return;
            if (!TTTrackingConsent.isCurrent(generation)) {
                cancelForConsent();
                return;
            }
            resultCode = code;
            resultMessage = message;
            RuntimeException failure = null;
            while (!pending.isEmpty() && !finished) {
                if (!TTTrackingConsent.isCurrent(generation)) {
                    cancelForConsent();
                    break;
                }
                failure = notifyNext(code, message, failure);
            }
            finished = true;
            if (failure != null) throw failure;
        }
    }

    private RuntimeException notifyNext(
            int code, String message, RuntimeException previousFailure) {
        // Remove before invoking application code, which may reenter withdrawal or initialization.
        TikTokBusinessSdk.TTInitCallback callback = pending.removeFirst();
        try {
            deliver(callback, code, message);
        } catch (RuntimeException error) {
            if (previousFailure == null) {
                previousFailure =
                        new IllegalStateException("TikTok initialization callback failed", error);
            } else {
                previousFailure.addSuppressed(error);
            }
        }
        return previousFailure;
    }

    private static void deliver(
            TikTokBusinessSdk.TTInitCallback callback, int code, String message) {
        if (message == null) callback.success();
        else callback.fail(code, message);
    }
}

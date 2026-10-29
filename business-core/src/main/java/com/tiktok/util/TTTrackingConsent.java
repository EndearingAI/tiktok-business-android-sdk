package com.tiktok.util;

/** Process-local consent authority. Remote configuration cannot grant consent. */
public final class TTTrackingConsent {
    public static final Object LOCK = new Object();
    private static volatile boolean enabled;
    private static volatile long generation;
    private static final ThreadLocal<Long> workGeneration = new ThreadLocal<>();

    private TTTrackingConsent() {}

    public static boolean isGranted() {
        Long work = workGeneration.get();
        return enabled && (work == null || work == generation);
    }

    public static long capture() {
        synchronized (LOCK) {
            return isGranted() ? generation : -1;
        }
    }

    public static boolean isCurrent(long captured) {
        return captured >= 0 && captured == generation && isGranted();
    }

    /** Called only by the public SDK consent setter, while holding LOCK. */
    public static void setEnabled(boolean granted) {
        synchronized (LOCK) {
            if (enabled != granted) {
                generation++;
                enabled = granted;
            }
        }
    }

    public static Runnable wrap(Runnable work) {
        return wrap(capture(), work);
    }

    public static Runnable wrap(long captured, Runnable work) {
        return () -> {
            if (!isCurrent(captured)) return;
            Long previous = workGeneration.get();
            workGeneration.set(captured);
            try {
                if (isCurrent(captured)) work.run();
            } finally {
                if (previous == null) workGeneration.remove();
                else workGeneration.set(previous);
            }
        };
    }

    public static <T> T call(long captured, java.util.concurrent.Callable<T> work, T denied) {
        if (!isCurrent(captured)) return denied;
        Long previous = workGeneration.get();
        workGeneration.set(captured);
        try {
            T result = isCurrent(captured) ? work.call() : denied;
            return isCurrent(captured) ? result : denied;
        } catch (Exception error) {
            throw new IllegalStateException("Consent-scoped SDK operation failed", error);
        } finally {
            if (previous == null) workGeneration.remove();
            else workGeneration.set(previous);
        }
    }

    /** Short state mutations share the same lock as withdrawal and network starts. */
    public static void runIfGranted(Runnable work) {
        synchronized (LOCK) {
            if (isGranted()) work.run();
        }
    }
}

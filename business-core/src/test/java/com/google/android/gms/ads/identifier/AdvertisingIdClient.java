package com.google.android.gms.ads.identifier;

import android.content.Context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Controllable external identifier service for native generation-fence tests. */
public final class AdvertisingIdClient {
    public static volatile String value = "current-gaid";
    public static volatile CountDownLatch entered;
    public static volatile CountDownLatch release;

    public static Info getAdvertisingIdInfo(Context context) throws InterruptedException {
        String requestedValue = value;
        CountDownLatch currentEntered = entered;
        CountDownLatch currentRelease = release;
        if (currentEntered != null) currentEntered.countDown();
        if (currentRelease != null && !currentRelease.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Identifier service test was not released");
        }
        return new Info(requestedValue);
    }

    public static final class Info {
        private final String id;

        Info(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public boolean isLimitAdTrackingEnabled() {
            return false;
        }
    }
}

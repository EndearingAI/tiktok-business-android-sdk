package com.tiktok.appevents;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tiktok.util.TTTrackingConsent;

import java.lang.ref.WeakReference;

public class TTLifecycleListener implements Application.ActivityLifecycleCallbacks {
    private final long consentGeneration = TTTrackingConsent.capture();

    private static volatile WeakReference<Activity> mActivityReference;

    @Override
    public void onActivityCreated(
            @NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!TTTrackingConsent.isCurrent(consentGeneration)) return;
            TTTrackingConsent.runIfGranted(
                    () -> mActivityReference = new WeakReference<>(activity));
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {}

    public static WeakReference<Activity> getActivityRef() {
        return TTTrackingConsent.isGranted() ? mActivityReference : null;
    }

    public static void clear() {
        mActivityReference = null;
    }
}

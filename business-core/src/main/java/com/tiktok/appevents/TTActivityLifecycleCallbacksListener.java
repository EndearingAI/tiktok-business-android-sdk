/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.tiktok.iap.TTInAppPurchaseWrapper;
import com.tiktok.util.JSON;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.json.JSONObject;

class TTActivityLifecycleCallbacksListener extends TTLifeCycleCallbacksAdapter {

    private final TTAppEventLogger appEventLogger;
    private static boolean isPaused = false;

    private long fgStart;
    private long bgStart = 0;

    public TTActivityLifecycleCallbacksListener(TTAppEventLogger appEventLogger) {
        this.appEventLogger = appEventLogger;
        this.fgStart = System.currentTimeMillis();
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!appEventLogger.isActive()) return;
            if (isPaused) {
                reportBackground(bgStart);
                fgStart = System.currentTimeMillis();
                appEventLogger.fetchGlobalConfig(0);
                appEventLogger.restartScheduler();
                appEventLogger.autoEventsManager.track2DayRetentionEvent();
            }
            isPaused = false;
        }
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!appEventLogger.isActive()) return;
            reportForeground(fgStart);
            bgStart = System.currentTimeMillis();
            appEventLogger.stopScheduler();
            isPaused = true;
            TTInAppPurchaseWrapper.registerIapTrack();
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!appEventLogger.isActive()) return;
            appEventLogger.persistEvents();
            appEventLogger.persistMonitor();
        }
    }

    // TODO might never be called as per Android's doc
    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        appEventLogger.stopScheduler();
    }

    private void reportForeground(long ts) {
        try {
            long latency = System.currentTimeMillis() - ts;
            JSONObject meta = TTUtil.getMetaWithTS(ts);
            JSON.putLong(meta, "latency", latency);
            appEventLogger.monitorMetric("foreground", meta, null);
        } catch (Throwable ignored) {
        }
    }

    private void reportBackground(long ts) {
        try {
            long latency = System.currentTimeMillis() - ts;
            JSONObject meta = TTUtil.getMetaWithTS(ts);
            JSON.putLong(meta, "latency", latency);
            appEventLogger.monitorMetric("background", meta, null);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isBackground() {
        return isPaused;
    }
}

/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class TTEdpAppEventsQueue {

    private static final String TAG = "TTEdpAppEventsQueue";
    private static List<TTAppEvent> memory = new CopyOnWriteArrayList<>();

    private TTEdpAppEventsQueue() {}

    private static void notifyChange() {
        if (!TTTrackingConsent.isGranted()) return;
        if (TikTokBusinessSdk.memoryListener != null) {
            TikTokBusinessSdk.memoryListener.onMemoryChange(memory.size());
        }

        if (TikTokBusinessSdk.nextTimeFlushListener != null) {
            int left = TTAppEventLogger.THRESHOLD - size();
            TikTokBusinessSdk.nextTimeFlushListener.thresholdLeft(
                    TTAppEventLogger.THRESHOLD, Math.max(left, 0));
        }
    }

    public static void addEvent(TTAppEvent event) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            TTUtil.checkThread(TAG);
            memory.add(event);
            notifyChange();
        }
    }

    public static int size() {
        return memory.size();
    }

    public static void clearAll() {
        try {
            TTUtil.checkThread(TAG);
            memory = new CopyOnWriteArrayList<>();
            notifyChange();
        } catch (Throwable ignore) {
        }
    }

    public static List<TTAppEvent> exportAllEvents() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return new java.util.ArrayList<>();

            List<TTAppEvent> appEvents = memory;
            memory = new CopyOnWriteArrayList<>();
            notifyChange();
            return appEvents;
        }
    }
}
